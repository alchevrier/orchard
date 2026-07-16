package com.orchard.backend.workspace

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant

internal fun <T> loadRecoverableJsonl(
    path: Path,
    quarantineName: String,
    decode: (line: String, recordNumber: Int) -> T,
): List<T> {
    if (!Files.exists(path)) return emptyList()
    val lines = Files.readAllLines(path, Charsets.UTF_8)
    val values = mutableListOf<T>()
    lines.forEachIndexed { index, line ->
        if (line.isBlank()) return@forEachIndexed
        try {
            values += decode(line, values.size + 1)
        } catch (exception: Exception) {
            if (lines.drop(index + 1).any { it.isNotBlank() }) {
                throw IllegalStateException("Corrupt interior record ${values.size + 1} in ${path.fileName}", exception)
            }
            quarantineJsonlTail(path, quarantineName, lines, index)
            return values
        }
    }
    return values
}

private fun quarantineJsonlTail(path: Path, quarantineName: String, lines: List<String>, firstInvalidLine: Int) {
    val directory = path.parent
    val quarantinePath = directory.resolve("$quarantineName.corrupt-${Instant.now().toEpochMilli()}.jsonl")
    val tail = lines.drop(firstInvalidLine).joinToString(separator = "\n", postfix = "\n")
    writeJsonlAtomically(quarantinePath, tail)
    val prefix = lines.take(firstInvalidLine).joinToString(
        separator = "\n",
        postfix = if (firstInvalidLine == 0) "" else "\n",
    )
    writeJsonlAtomically(path, prefix)
}

private fun writeJsonlAtomically(path: Path, content: String) {
    Files.createDirectories(path.parent)
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
        Files.move(temporaryPath, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(temporaryPath, path, StandardCopyOption.REPLACE_EXISTING)
    }
    runCatching { FileChannel.open(path.parent, StandardOpenOption.READ).use { it.force(true) } }
}