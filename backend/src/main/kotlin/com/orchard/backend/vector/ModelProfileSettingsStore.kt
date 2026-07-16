package com.orchard.backend.vector

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

@Serializable
data class ModelProfileOverride(
    val profileId: String,
    val inputBudgetTokens: Int,
    val outputBudgetTokens: Int,
    val preferredBindingId: String? = null,
)

@Serializable
data class ModelProfileConfiguration(
    val defaultProfile: ModelExecutionProfile,
    val effectiveProfile: ModelExecutionProfile,
    val override: ModelProfileOverride? = null,
    val installedBindings: List<ModelBindingProfile>,
    val compatibleBindingIds: List<String>,
)

enum class ModelProfileUpdateStatus {
    UPDATED,
    PROFILE_NOT_FOUND,
    INVALID_BUDGET,
    NO_COMPATIBLE_BINDING,
    STORAGE_UNAVAILABLE,
}

data class ModelProfileUpdateResult(
    val status: ModelProfileUpdateStatus,
    val configurations: List<ModelProfileConfiguration>,
)

interface ModelProfileSettingsStore {
    fun load(): List<ModelProfileOverride>
    fun save(overrides: List<ModelProfileOverride>)
}

class TransientModelProfileSettingsStore : ModelProfileSettingsStore {
    private var overrides = emptyList<ModelProfileOverride>()

    @Synchronized
    override fun load(): List<ModelProfileOverride> = overrides

    @Synchronized
    override fun save(overrides: List<ModelProfileOverride>) {
        this.overrides = overrides.toList()
    }
}

class FileModelProfileSettingsStore(private val directory: Path) : ModelProfileSettingsStore {
    private val path = directory.resolve("model-profile-settings.json")
    private val json = Json { encodeDefaults = true; prettyPrint = true }
    private val compactJson = Json { encodeDefaults = true }

    @Synchronized
    override fun load(): List<ModelProfileOverride> {
        if (!Files.exists(path)) return emptyList()
        val envelope = runCatching {
            json.decodeFromString<ModelProfileSettingsEnvelope>(Files.readString(path, Charsets.UTF_8))
        }.getOrElse { throw IllegalStateException("Cannot decode model profile settings at $path", it) }
        require(envelope.version == FORMAT_VERSION) { "Unsupported model profile settings version ${envelope.version}" }
        require(envelope.checksum == checksum(compactJson.encodeToString(envelope.overrides))) {
            "Model profile settings checksum mismatch at $path"
        }
        require(envelope.overrides.map { it.profileId }.distinct().size == envelope.overrides.size) {
            "Model profile settings contain duplicate profile IDs"
        }
        require(envelope.overrides.all {
            it.profileId.isNotBlank() &&
                it.inputBudgetTokens in 1_024..1_000_000 &&
                it.outputBudgetTokens in 256..1_000_000
        }) { "Model profile settings contain invalid budgets" }
        return envelope.overrides
    }

    @Synchronized
    override fun save(overrides: List<ModelProfileOverride>) {
        require(overrides.map { it.profileId }.distinct().size == overrides.size) {
            "Model profile settings contain duplicate profile IDs"
        }
        val sorted = overrides.sortedBy { it.profileId }
        val envelope = ModelProfileSettingsEnvelope(
            overrides = sorted,
            checksum = checksum(compactJson.encodeToString(sorted)),
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
private data class ModelProfileSettingsEnvelope(
    val version: Int = 1,
    val overrides: List<ModelProfileOverride>,
    val checksum: String,
)
