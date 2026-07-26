package com.orchard.backend.analysis

import com.orchard.backend.workspace.loadRecoverableJsonl
import com.orchard.backend.workspace.stagedPlanHash
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface ExecutableWorkPackageStore {
    fun load(): List<ExecutableWorkPackage>
    fun append(packageAuthority: ExecutableWorkPackage)
    fun appendNext(runId: Long, create: (packageId: Long, revision: Int) -> ExecutableWorkPackage): ExecutableWorkPackage
}

class TransientExecutableWorkPackageStore : ExecutableWorkPackageStore {
    private val packages = mutableListOf<ExecutableWorkPackage>()

    @Synchronized
    override fun load(): List<ExecutableWorkPackage> = packages.toList()

    @Synchronized
    override fun append(packageAuthority: ExecutableWorkPackage) {
        validateExecutableWorkPackageRecord(packageAuthority, packages)
        packages += packageAuthority
    }

    @Synchronized
    override fun appendNext(
        runId: Long,
        create: (packageId: Long, revision: Int) -> ExecutableWorkPackage,
    ): ExecutableWorkPackage {
        val packageAuthority = create(packages.size + 1L, packages.count { it.runId == runId } + 1)
        append(packageAuthority)
        return packageAuthority
    }
}

class FileExecutableWorkPackageStore(private val directory: Path) : ExecutableWorkPackageStore {
    private val path = directory.resolve("executable-work-packages.jsonl")
    private val lockPath = directory.resolve("executable-work-packages.lock")
    private val json = Json { encodeDefaults = true }

    @Synchronized
    override fun load(): List<ExecutableWorkPackage> {
        Files.createDirectories(directory)
        return FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { lock ->
            lock.lock().use { loadUnlocked() }
        }
    }

    @Synchronized
    override fun append(packageAuthority: ExecutableWorkPackage) {
        Files.createDirectories(directory)
        FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { lock ->
            lock.lock().use {
                val packages = loadUnlocked()
                validateExecutableWorkPackageRecord(packageAuthority, packages)
                appendUnlocked(packageAuthority)
            }
        }
    }

    @Synchronized
    override fun appendNext(
        runId: Long,
        create: (packageId: Long, revision: Int) -> ExecutableWorkPackage,
    ): ExecutableWorkPackage {
        Files.createDirectories(directory)
        return FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { lock ->
            lock.lock().use {
                val packages = loadUnlocked()
                val packageAuthority = create(packages.size + 1L, packages.count { it.runId == runId } + 1)
                validateExecutableWorkPackageRecord(packageAuthority, packages)
                appendUnlocked(packageAuthority)
                packageAuthority
            }
        }
    }

    private fun loadUnlocked(): List<ExecutableWorkPackage> = mutableListOf<ExecutableWorkPackage>().also { packages ->
        loadRecoverableJsonl(path, "executable-work-packages") { line, recordNumber ->
            val envelope = json.decodeFromString<ExecutableWorkPackageEnvelope>(line)
            require(envelope.version == STORE_FORMAT_VERSION) {
                "Unsupported executable work-package store format ${envelope.version}"
            }
            require(envelope.checksum == stagedPlanHash(json.encodeToString(envelope.value))) {
                "Checksum mismatch in executable work package $recordNumber"
            }
            validateExecutableWorkPackageRecord(envelope.value, packages)
            packages += envelope.value
            envelope.value
        }
    }

    private fun appendUnlocked(packageAuthority: ExecutableWorkPackage) {
        val payload = json.encodeToString(packageAuthority)
        val line = json.encodeToString(
            ExecutableWorkPackageEnvelope(value = packageAuthority, checksum = stagedPlanHash(payload))
        ) + "\n"
        FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { channel ->
            val bytes = ByteBuffer.wrap(line.toByteArray(Charsets.UTF_8))
            while (bytes.hasRemaining()) channel.write(bytes)
            channel.force(true)
        }
        FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
    }
}

private fun validateExecutableWorkPackageRecord(
    packageAuthority: ExecutableWorkPackage,
    previous: List<ExecutableWorkPackage>,
) {
    require(packageAuthority.packageId == previous.size + 1L) { "Executable work-package ID is not monotonic" }
    val priorRunPackages = previous.filter { it.runId == packageAuthority.runId }
    require(packageAuthority.revision == (priorRunPackages.maxOfOrNull { it.revision } ?: 0) + 1) {
        "Executable work-package revision is not monotonic"
    }
    val adequacy = verifyExecutableWorkPackage(packageAuthority)
    require(adequacy.adequate) { "Executable work package is inadequate: ${adequacy.diagnostics.joinToString(" ")}" }
}

@Serializable
private data class ExecutableWorkPackageEnvelope(
    val version: Int = STORE_FORMAT_VERSION,
    val value: ExecutableWorkPackage,
    val checksum: String,
)

private const val STORE_FORMAT_VERSION = 1