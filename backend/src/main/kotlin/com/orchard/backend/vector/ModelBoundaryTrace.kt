package com.orchard.backend.vector

import com.orchard.backend.workspace.stagedPlanHash
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ModelBoundaryTraceEvent(
    val formatVersion: Int = 1,
    val traceId: String,
    val runId: Long?,
    val sequence: Int,
    val stage: String,
    val payload: String,
    val payloadHash: String,
    val originalBytes: Int,
    val capturedBytes: Int,
    val truncated: Boolean,
    val details: Map<String, String>,
    val recordedAt: String = Instant.now().toString(),
    val projectId: Int? = null,
)

class ModelBoundaryTraceException(message: String, cause: Exception) : IOException(message, cause)

class ModelBoundaryTraceStore(
    val directory: Path,
    private val maxPayloadBytes: Int = 256 * 1024,
    private val maxTraceBytes: Long = 4L * 1024 * 1024,
    private val maxTraces: Int = 32,
) {
    init {
        require(directory.isAbsolute) { "ORCHARD_MODEL_TRACE_DIR must be an absolute dedicated directory" }
        require(maxPayloadBytes > 0 && maxTraceBytes > 0 && maxTraces > 0)
        require(directory.fileSystem.supportedFileAttributeViews().contains("posix")) {
            "Model payload tracing requires POSIX private file permissions"
        }
        Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(PRIVATE_DIRECTORY_PERMISSIONS))
        require(!Files.isSymbolicLink(directory) &&
            Files.getPosixFilePermissions(directory).all { it in PRIVATE_DIRECTORY_PERMISSIONS }) {
            "Model payload trace directory must be private and not a symbolic link"
        }
    }

    fun newTrace(runId: Long): ModelBoundaryTrace =
        ModelBoundaryTrace(this, "analysis-$runId-${UUID.randomUUID()}", runId)

    fun newGenesisTrace(projectId: Int): ModelBoundaryTrace =
        ModelBoundaryTrace(this, "genesis-$projectId-${UUID.randomUUID()}", null, projectId)

    @Synchronized
    internal fun append(
        trace: ModelBoundaryTrace,
        sequence: Int,
        stage: String,
        payload: String,
        details: Map<String, String>,
    ) {
        val path = directory.resolve("${trace.traceId}.jsonl")
        try {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                val count = Files.list(directory).use { entries ->
                    entries.filter {
                        val name = it.fileName.toString()
                        (name.startsWith("analysis-") || name.startsWith("genesis-")) && name.endsWith(".jsonl")
                    }.count()
                }
                if (count >= maxTraces) throw IOException("Trace file quota reached ($maxTraces); archive diagnostics before continuing")
            } else if (Files.isSymbolicLink(path) ||
                Files.getPosixFilePermissions(path).any { it !in PRIVATE_FILE_PERMISSIONS }) {
                throw IOException("Trace file must be private and not a symbolic link")
            }
            val bytes = payload.toByteArray(Charsets.UTF_8)
            var end = minOf(bytes.size, maxPayloadBytes)
            if (end < bytes.size) {
                while (end > 0 && (bytes[end].toInt() and 0xc0) == 0x80) end--
            }
            val event = ModelBoundaryTraceEvent(
                traceId = trace.traceId, runId = trace.runId, projectId = trace.projectId, sequence = sequence, stage = stage,
                payload = String(bytes, 0, end, Charsets.UTF_8), payloadHash = stagedPlanHash(payload),
                originalBytes = bytes.size, capturedBytes = end, truncated = end != bytes.size, details = details,
            )
            val line = (Json.encodeToString(event) + "\n").toByteArray(Charsets.UTF_8)
            val options = setOf<OpenOption>(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS)
            FileChannel.open(path, options, PosixFilePermissions.asFileAttribute(PRIVATE_FILE_PERMISSIONS)).use { channel ->
                if (channel.size() + line.size > maxTraceBytes) throw IOException("Trace byte quota reached ($maxTraceBytes): ${trace.traceId}")
                val buffer = ByteBuffer.wrap(line)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(false)
            }
        } catch (error: IOException) {
            val failure = ModelBoundaryTraceException("Model boundary trace ${trace.traceId} is incomplete: ${error.message}", error)
            logger.error(failure.message, error)
            throw failure
        } catch (error: SecurityException) {
            val failure = ModelBoundaryTraceException("Model boundary trace ${trace.traceId} is inaccessible: ${error.message}", error)
            logger.error(failure.message, error)
            throw failure
        }
    }

    private companion object {
        val PRIVATE_DIRECTORY_PERMISSIONS = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
        val PRIVATE_FILE_PERMISSIONS = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        val logger = org.slf4j.LoggerFactory.getLogger(ModelBoundaryTraceStore::class.java)
    }
}

class ModelBoundaryTrace internal constructor(
    private val store: ModelBoundaryTraceStore,
    val traceId: String,
    val runId: Long?,
    val projectId: Int? = null,
) : AbstractCoroutineContextElement(Key) {
    private var sequence = 0

    @Synchronized
    fun record(stage: String, payload: String, details: Map<String, String> = emptyMap()) {
        store.append(this, ++sequence, stage, payload, details)
    }

    companion object Key : CoroutineContext.Key<ModelBoundaryTrace>
}
