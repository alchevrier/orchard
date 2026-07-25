package com.orchard.backend.agent

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
        assertTrue(context.files.sumOf { it.content.encodeToByteArray().size } <= 4 * 1024)
        assertTrue(context.omittedFileCount >= 6)
    }

    @Test
    fun `genesis context excludes illustrative and verification code`() {
        val repository = createTempDirectory("orchard-genesis-production-context-")
        git(repository, "init")
        val production = repository.resolve("core/src/main/kotlin/OrderBook.kt")
        Files.createDirectories(production.parent)
        Files.writeString(production, "class OrderBook\n")
        listOf(
            "autumn-sandbox/src/main/kotlin/ShardedITCHSandbox.kt",
            "benchmarks/src/jvmMain/kotlin/ItchBenchmark.kt",
            "core/src/test/kotlin/OrderBookTest.kt",
            "examples/src/main/kotlin/OrderBookExample.kt",
            "docs/order-book.md",
        ).forEach { relative ->
            repository.resolve(relative).also { file ->
                Files.createDirectories(file.parent)
                Files.writeString(file, "class OrderBookFixture\n")
            }
        }
        git(repository, "add", ".")

        val context = LocalCodingWorkspaceGateway().collectGenesisContext(repository.toString(), "order book")

        assertEquals(listOf("core/src/main/kotlin/OrderBook.kt"), context.files.map { it.path })
    }

    @Test
    fun `plan context includes every pinned path within the bounded coding aperture`() {
        val repository = createTempDirectory("orchard-plan-context-")
        git(repository, "init")
        val paths = (1..5).map { index -> "src/Owner$index.kt" }
        Files.createDirectories(repository.resolve("src"))
        paths.forEachIndexed { index, path ->
            Files.writeString(
                repository.resolve(path),
                "class Owner${index + 1}\n" + "val unrelated${index + 1} = 1\n".repeat(8_000),
            )
        }
        git(repository, "add", ".")
        git(repository, "-c", "user.name=Orchard Test", "-c", "user.email=orchard@example.test", "commit", "-m", "Initial")
        val revision = gitOutput(repository, "rev-parse", "HEAD")

        val context = LocalCodingWorkspaceGateway().collectPlanContext(
            repository.toString(),
            revision,
            paths,
            "native platform typography",
            12 * 1024,
        )

        assertEquals(paths, context.files.map { it.path })
        assertEquals(0, context.omittedFileCount)
        assertTrue(context.files.all { it.contentHash.matches(Regex("[0-9a-f]{64}")) })
        assertTrue(context.files.all { it.content.isNotEmpty() })
        assertTrue(contextJson.encodeToString(context).encodeToByteArray().size <= 12 * 1024)
    }

    @Test
    fun `plan context requires editable source for every pinned corrective path`() {
        val repository = createTempDirectory("orchard-plan-retry-context-")
        git(repository, "init")
        val paths = (1..5).map { index ->
            "frontend/src/desktopMain/kotlin/com/orchard/frontend/ui/Owner$index.kt"
        }
        Files.createDirectories(repository.resolve("frontend/src/desktopMain/kotlin/com/orchard/frontend/ui"))
        paths.forEachIndexed { index, path ->
            Files.writeString(
                repository.resolve(path),
                "class Owner${index + 1}\n" + "val platformTypography${index + 1} = true\n".repeat(200),
            )
        }
        git(repository, "add", ".")
        git(repository, "-c", "user.name=Orchard Test", "-c", "user.email=orchard@example.test", "commit", "-m", "Initial")
        val revision = gitOutput(repository, "rev-parse", "HEAD")

        assertFailsWith<IllegalArgumentException> {
            LocalCodingWorkspaceGateway().collectPlanContext(
                repository.toString(),
                revision,
                paths,
                "platform typography",
                1_600,
            )
        }
        val context = LocalCodingWorkspaceGateway().collectPlanContext(
            repository.toString(),
            revision,
            paths,
            "platform typography",
            3_200,
        )

        assertEquals(paths, context.files.map { it.path })
        assertEquals(0, context.omittedFileCount)
        assertTrue(context.files.all { it.contentHash.matches(Regex("[0-9a-f]{64}")) })
        assertTrue(context.files.all { it.content.contains("class Owner") })
        assertTrue(context.files.all { it.content.encodeToByteArray().size >= 128 })
        assertTrue(contextJson.encodeToString(context).encodeToByteArray().size <= 3_200)
    }

    private companion object {
        val contextJson = Json { encodeDefaults = true }
    }

    private fun git(directory: Path, vararg arguments: String) {
        gitOutput(directory, *arguments)
    }

    private fun gitOutput(directory: Path, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git", "-C", directory.toString()) + arguments)
            .redirectErrorStream(true)
            .start()
        assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        assertEquals(0, process.exitValue(), output)
        return output
    }
}