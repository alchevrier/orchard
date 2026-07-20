package com.orchard.backend.agent

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodingWorkspaceGatewayTest {
    @Test
    fun `roadmap and documentation indexes remain in bounded foundation context`() {
        val repository = createTempDirectory("orchard-roadmap-context-")
        git(repository, "init")
        Files.writeString(repository.resolve("ROADMAP.md"), "# Roadmap\n\nCurrent milestone: 10.1\n")
        Files.writeString(repository.resolve("README.md"), "# Example\n")
        Files.createDirectories(repository.resolve("docs/user-guide"))
        Files.createDirectories(repository.resolve("docs/developer"))
        Files.createDirectories(repository.resolve("docs/adrs"))
        Files.writeString(repository.resolve("docs/README.md"), "# Documentation\n")
        Files.writeString(repository.resolve("docs/user-guide/README.md"), "# User Guide\n")
        Files.writeString(repository.resolve("docs/developer/README.md"), "# Developer Documentation\n")
        repeat(40) { index ->
            Files.writeString(repository.resolve("docs/adrs/${index.toString().padStart(3, '0')}.md"), "# Decision $index\n")
        }
        repeat(40) { index ->
            Files.writeString(repository.resolve("source-${index.toString().padStart(2, '0')}.kt"), "class Source$index\n")
        }
        git(repository, "add", ".")

        val context = LocalCodingWorkspaceGateway().collectContext(repository.toString(), "unrelated implementation detail")

        assertTrue(context.files.any { it.path == "ROADMAP.md" })
        assertTrue(context.files.any { it.path == "docs/README.md" })
        assertTrue(context.files.any { it.path == "docs/user-guide/README.md" })
        assertTrue(context.files.any { it.path == "docs/developer/README.md" })
        assertTrue(context.omittedFileCount > 0)
    }

    @Test
    fun `genesis context stays within proposal aperture`() {
        val repository = createTempDirectory("orchard-genesis-context-")
        git(repository, "init")
        repeat(12) { index ->
            Files.writeString(
                repository.resolve("component-$index.kt"),
                "class Component$index\n" + "x".repeat(4_000),
            )
        }
        git(repository, "add", ".")

        val context = LocalCodingWorkspaceGateway().collectGenesisContext(repository.toString(), "component")

        assertTrue(context.files.size <= 6)
        assertTrue(context.files.sumOf { it.content.encodeToByteArray().size } <= 24 * 1024)
        assertTrue(context.omittedFileCount >= 6)
    }

    private fun git(directory: Path, vararg arguments: String) {
        val process = ProcessBuilder(listOf("git", "-C", directory.toString()) + arguments)
            .redirectErrorStream(true)
            .start()
        assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        assertEquals(0, process.exitValue(), output)
    }
}