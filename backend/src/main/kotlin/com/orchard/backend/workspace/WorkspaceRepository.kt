package com.orchard.backend.workspace

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

private const val WORKSPACE_FORMAT_VERSION = 1

interface WorkspaceRepository {
    fun load(): List<WorkspaceEntity>
    fun commit(newEntities: List<WorkspaceEntity>, allEntities: List<WorkspaceEntity>)
}

object TransientWorkspaceRepository : WorkspaceRepository {
    override fun load(): List<WorkspaceEntity> = emptyList()
    override fun commit(newEntities: List<WorkspaceEntity>, allEntities: List<WorkspaceEntity>) = Unit
}

class FileWorkspaceRepository(
    private val directory: Path,
    private val compactAfterTransactions: Int = 32,
) : WorkspaceRepository {
    private val journalPath = directory.resolve("workspace.journal.jsonl")
    private val snapshotPath = directory.resolve("workspace.snapshot.json")
    private val compactJson = Json { encodeDefaults = true }
    private val prettyJson = Json { encodeDefaults = true; prettyPrint = true }
    private var currentSequence = 0L
    private var transactionsSinceSnapshot = 0

    init {
        require(compactAfterTransactions > 0) { "Compaction threshold must be positive" }
    }

    @Synchronized
    override fun load(): List<WorkspaceEntity> {
        Files.createDirectories(directory)
        val snapshot = readSnapshot()
        val entities = snapshot?.entities?.toMutableList() ?: mutableListOf()
        currentSequence = snapshot?.sequence ?: 0L
        transactionsSinceSnapshot = 0

        if (!Files.exists(journalPath)) return entities
        val lines = Files.readAllLines(journalPath, Charsets.UTF_8)
        var lineIndex = 0
        while (lineIndex < lines.size) {
            val line = lines[lineIndex]
            val record = try {
                decodeJournalRecord(line)
            } catch (exception: Exception) {
                quarantineJournalTail(lines, lineIndex, exception)
                break
            }
            if (record.transaction.sequence <= currentSequence) {
                lineIndex++
                continue
            }
            if (record.transaction.sequence != currentSequence + 1) {
                quarantineJournalTail(
                    lines,
                    lineIndex,
                    IllegalStateException("Expected journal sequence ${currentSequence + 1}, found ${record.transaction.sequence}"),
                )
                break
            }
            entities += record.transaction.entities
            currentSequence = record.transaction.sequence
            transactionsSinceSnapshot++
            lineIndex++
        }
        return entities
    }

    @Synchronized
    override fun commit(newEntities: List<WorkspaceEntity>, allEntities: List<WorkspaceEntity>) {
        if (newEntities.isEmpty()) return
        Files.createDirectories(directory)
        val transaction = JournalTransaction(
            sequence = currentSequence + 1,
            entities = newEntities,
        )
        val record = JournalRecord(transaction, checksum(compactJson.encodeToString(transaction)))
        appendDurably(compactJson.encodeToString(record) + "\n")
        currentSequence = transaction.sequence
        transactionsSinceSnapshot++

        if (transactionsSinceSnapshot >= compactAfterTransactions) {
            runCatching { compact(allEntities) }
                .onFailure { logger.warn("Workspace snapshot compaction failed; journal remains authoritative", it) }
        }
    }

    private fun readSnapshot(): SnapshotState? {
        if (!Files.exists(snapshotPath)) return null
        val envelope = try {
            prettyJson.decodeFromString<SnapshotEnvelope>(Files.readString(snapshotPath, Charsets.UTF_8))
        } catch (exception: Exception) {
            throw IllegalStateException("Cannot decode workspace snapshot at $snapshotPath", exception)
        }
        val expectedChecksum = checksum(compactJson.encodeToString(envelope.state))
        check(envelope.checksum == expectedChecksum) { "Workspace snapshot checksum mismatch at $snapshotPath" }
        check(envelope.state.version == WORKSPACE_FORMAT_VERSION) {
            "Unsupported workspace snapshot version ${envelope.state.version} at $snapshotPath"
        }
        return envelope.state
    }

    private fun decodeJournalRecord(line: String): JournalRecord {
        require(line.isNotBlank()) { "Blank workspace journal record" }
        val record = compactJson.decodeFromString<JournalRecord>(line)
        val expectedChecksum = checksum(compactJson.encodeToString(record.transaction))
        require(record.checksum == expectedChecksum) { "Workspace journal checksum mismatch" }
        require(record.transaction.version == WORKSPACE_FORMAT_VERSION) {
            "Unsupported workspace journal version ${record.transaction.version}"
        }
        return record
    }

    private fun appendDurably(line: String) {
        FileChannel.open(
            journalPath,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.APPEND,
        ).use { channel ->
            val bytes = ByteBuffer.wrap(line.toByteArray(Charsets.UTF_8))
            while (bytes.hasRemaining()) channel.write(bytes)
            channel.force(true)
        }
    }

    private fun compact(entities: List<WorkspaceEntity>) {
        val state = SnapshotState(sequence = currentSequence, entities = entities)
        val envelope = SnapshotEnvelope(state, checksum(compactJson.encodeToString(state)))
        writeAtomically(snapshotPath, prettyJson.encodeToString(envelope) + "\n")
        FileChannel.open(
            journalPath,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING,
        ).use { it.force(true) }
        transactionsSinceSnapshot = 0
    }

    private fun quarantineJournalTail(lines: List<String>, firstInvalidLine: Int, cause: Exception) {
        val quarantinePath = directory.resolve(
            "workspace.journal.corrupt-${Instant.now().toEpochMilli()}.jsonl"
        )
        val tail = lines.drop(firstInvalidLine).joinToString(separator = "\n", postfix = "\n")
        writeAtomically(quarantinePath, tail)
        val validPrefix = lines.take(firstInvalidLine)
            .joinToString(separator = "\n", postfix = if (firstInvalidLine == 0) "" else "\n")
        writeAtomically(journalPath, validPrefix)
        logger.warn("Quarantined corrupt workspace journal tail to {}", quarantinePath, cause)
    }

    private fun writeAtomically(path: Path, content: String) {
        val temporaryPath = path.resolveSibling("${path.fileName}.tmp")
        FileChannel.open(
            temporaryPath,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING,
        ).use { channel ->
            val bytes = ByteBuffer.wrap(content.toByteArray(Charsets.UTF_8))
            while (bytes.hasRemaining()) channel.write(bytes)
            channel.force(true)
        }
        try {
            Files.move(
                temporaryPath,
                path,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporaryPath, path, StandardCopyOption.REPLACE_EXISTING)
        }
        forceDirectory(path.parent)
    }

    private fun forceDirectory(path: Path) {
        runCatching {
            FileChannel.open(path, StandardOpenOption.READ).use { it.force(true) }
        }.onFailure {
            logger.debug("Directory metadata flush is not supported for {}", path, it)
        }
    }

    private fun checksum(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }

    private companion object {
        val logger = LoggerFactory.getLogger(FileWorkspaceRepository::class.java)
    }
}

@Serializable
private data class JournalTransaction(
    val version: Int = WORKSPACE_FORMAT_VERSION,
    val sequence: Long,
    val entities: List<WorkspaceEntity>,
)

@Serializable
private data class JournalRecord(
    val transaction: JournalTransaction,
    val checksum: String,
)

@Serializable
private data class SnapshotState(
    val version: Int = WORKSPACE_FORMAT_VERSION,
    val sequence: Long,
    val entities: List<WorkspaceEntity>,
)

@Serializable
private data class SnapshotEnvelope(
    val state: SnapshotState,
    val checksum: String,
)