package com.orchard.backend.agent

import com.orchard.backend.workspace.compileScopePathEvidenceSelectors
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class CodingWorkspaceGatewayTest {
    @Test
    fun `analysis collection uses complete Kotlin declarations before admission`() {
        val repository = createTempDirectory("orchard-analysis-declarations-")
        git(repository, "init")
        Files.createDirectories(repository.resolve("src"))
        val path = "src/Owner.kt"
        val entries = (1..1_000).joinToString(",\n") { "    STATUS_$it" }
        val source = "enum class Status {\n$entries\n}\nclass Owner {\n    fun answer(): Int {\n        return 42\n    }\n}\n"
        Files.writeString(repository.resolve(path), source)
        git(repository, "add", ".")
        val context = LocalCodingWorkspaceGateway().collectAnalysisContext(repository.toString(), "answer",
            compileScopePathEvidenceSelectors(listOf("Implement `$path` answer behavior."), emptyList()))

        assertEquals(null, repositoryContextAdequacyDiagnostic(context), context.files.single().content)
        assertTrue(context.files.single().content.contains("return 42"))
        assertEquals(sha256Content(source), context.files.single().contentHash)
        assertTrue(context.files.single().content.encodeToByteArray().size <= 12 * 1024)
    }

    @Test
    fun `pinned Kotlin collection supplies complete behavior instead of standalone enum entries`() {
        val repository = createTempDirectory("orchard-enum-evidence-")
        git(repository, "init")
        Files.createDirectories(repository.resolve("src"))
        val entries = (1..250).joinToString(",\n") { "    STATUS_$it" }
        val path = "src/Owner.kt"
        val source = "enum class Status {\n$entries\n}\nclass Owner {\n    fun answer(): Int {\n        return 42\n    }\n}\n"
        Files.writeString(repository.resolve(path), source)
        git(repository, "add", ".")
        git(repository, "-c", "user.name=Orchard Test", "-c", "user.email=orchard@example.test", "commit", "-m", "Initial")
        val gateway = LocalCodingWorkspaceGateway()
        val revision = gitOutput(repository, "rev-parse", "HEAD")
        val context = gateway.collectPlanContext(repository.toString(), revision, listOf(path), "answer", 768)

        assertEquals(null, repositoryContextAdequacyDiagnostic(context), context.files.single().content)
        assertTrue(context.files.single().content.contains("return 42"))
        assertEquals(sha256Content(source), context.files.single().contentHash)
        assertTrue(context.files.single().content.contains("// Orchard source lines "))
        assertTrue(contextJson.encodeToString(context).encodeToByteArray().size <= 768)

        Files.writeString(repository.resolve(path), "enum class Status {\n$entries\n}\n")
        git(repository, "add", ".")
        git(repository, "-c", "user.name=Orchard Test", "-c", "user.email=orchard@example.test", "commit", "-m", "No fitting behavior")
        val failure = assertFailsWith<IllegalArgumentException> {
            gateway.collectPlanContext(repository.toString(), gitOutput(repository, "rev-parse", "HEAD"), listOf(path), "status", 768)
        }
        assertTrue(failure.message.orEmpty().contains("SOURCE_EVIDENCE_INADEQUATE"), failure.message)
    }

    @Test
    fun `provenance annotation changes excerpt integrity but not pinned source identity`() {
        val source = CodingContextFile("src/Owner.kt", "fun answer() = 42\n")
        val annotated = source.copy(content = "// Orchard source lines 1-1\n${source.content}")
        val frame = kotlinx.serialization.json.JsonObject(emptyMap())
        fun report(file: CodingContextFile) = com.orchard.backend.attention.compileContextQualityReport(
            frame, CodingRepositoryContext(listOf(file), 0), setOf(source.path), null, 4_096,
        )
        val originalReport = report(source)
        val annotatedReport = report(annotated)
        val evidence = annotatedReport.evidence.single()

        assertEquals(source.contentHash, annotated.contentHash)
        assertEquals(source.contentHash, evidence.sourceHash)
        assertEquals(sha256Content(annotated.content), evidence.excerptHash)
        assertNotEquals(originalReport.evidence.single().excerptHash, evidence.excerptHash)
        assertTrue(evidence.excerptBytes > originalReport.evidence.single().excerptBytes)
        assertEquals(listOf("1-1"), evidence.originalLineRanges)
        assertNotEquals(com.orchard.backend.attention.contextQualityReportHash(originalReport),
            com.orchard.backend.attention.contextQualityReportHash(annotatedReport))
    }

    @Test
    fun `provenance annotation preserves required declaration and original source comment`() {
        val preamble = (1..100).joinToString("\n") { "import example.Dependency$it" } + "\n"
        val source = CodingContextFile("src/Owner.kt", preamble + "// Caller requires a stable answer.\nfun answer() = 42\n")
        val original = CodingRepositoryContext(listOf(source), 0)
        val paths = setOf(source.path)
        val anchors = repositoryEvidenceAnchors(original, paths)
        val annotated = source.copy(content = contextFileExcerpt(source, setOf("answer"), 256))
        val excerpt = CodingRepositoryContext(listOf(annotated), 0)

        assertTrue(annotated.content.startsWith("// Orchard source lines "))
        assertTrue(annotated.content.contains("// Caller requires a stable answer."))
        assertTrue(annotated.content.contains("fun answer() = 42"))
        assertEquals(source.contentHash, annotated.contentHash)
        assertNotEquals(sha256Content(source.content), sha256Content(annotated.content))
        assertEquals(anchors, repositoryEvidenceAnchors(excerpt, paths))
        assertEquals(null, repositoryEvidenceRetentionDiagnostic(anchors, excerpt))
    }

    @Test
    fun `provenance re-compaction keeps source coordinates without duplicating annotations`() {
        val preamble = (1..100).joinToString("\n") { "import example.Dependency$it" } + "\n"
        val source = CodingContextFile("src/Owner.kt", preamble + "// Caller requires a stable answer.\nfun answer() = 42\nfun unrelated() = 0\n")
        val annotated = source.copy(content = contextFileExcerpt(source, setOf("answer"), 256))
        val compacted = annotated.copy(content = contextFileExcerpt(annotated, setOf("answer"), 110))
        val expected = CodingRepositoryContext(listOf(CodingContextFile(source.path, "// Caller requires a stable answer.\nfun answer() = 42\n")), 0)
        val paths = setOf(source.path)

        assertTrue(compacted.content.startsWith("// Orchard source lines 101-102\n"), compacted.content)
        assertEquals(1, compacted.content.lineSequence().count { it.startsWith("// Orchard source lines ") })
        assertTrue(compacted.content.contains("// Caller requires a stable answer."))
        assertTrue(!compacted.content.contains("unrelated"))
        assertEquals(source.contentHash, compacted.contentHash)
        assertNotEquals(sha256Content(annotated.content), sha256Content(compacted.content))
        assertEquals(repositoryEvidenceAnchors(expected, paths), repositoryEvidenceAnchors(CodingRepositoryContext(listOf(compacted), 0), paths))
    }

    @Test
    fun `provenance retention detects original comment edits and behavioral edits independently`() {
        val source = CodingContextFile("src/Owner.kt", "fun answer(): Int {\n    // Caller requires a stable answer.\n    return 42\n}\n")
        val original = CodingRepositoryContext(listOf(source), 0)
        val anchors = repositoryEvidenceAnchors(original, setOf(source.path))
        for (content in listOf(
            source.content.replace("stable answer", "different answer"),
            source.content.replace("return 42", "return 43"),
        )) {
            val changed = source.copy(content = content)
            assertEquals(source.contentHash, changed.contentHash)
            assertNotEquals(sha256Content(source.content), sha256Content(changed.content))
            assertTrue(requireNotNull(repositoryEvidenceRetentionDiagnostic(anchors,
                CodingRepositoryContext(listOf(changed), 0))).contains(source.path))
        }
    }

    @Test
    fun `provenance shaped source comments and string literals remain original evidence`() {
        for (content in listOf(
            "// Orchard source lines 10-10\nfun answer() = 42\n",
            "fun answer(): Int {\n    // Orchard source lines 10-10\n    return 42\n}\n",
            "fun message() = \"\"\"\n// Orchard source lines 10-10\n\"\"\"\n",
        )) {
            val source = CodingContextFile("src/Owner.kt", content)
            val original = CodingRepositoryContext(listOf(source), 0)
            val paths = setOf(source.path)
            val anchors = repositoryEvidenceAnchors(original, paths)
            val changed = original.copy(files = listOf(source.copy(content = content.replace("10-10", "20-20"))))

            assertNotEquals(anchors, repositoryEvidenceAnchors(changed, paths))
            assertTrue(requireNotNull(repositoryEvidenceRetentionDiagnostic(anchors, changed)).contains(source.path))
        }
    }

    @Test
    fun `compaction preserves producer consumer and regression declarations or rejects fit`() {
        val original = CodingRepositoryContext(listOf(
            CodingContextFile("src/Producer.kt", "fun produce() = 42\nfun unrelated() = 0\n"),
            CodingContextFile("src/Consumer.kt", "fun consume() = produce() + 1\n"),
            CodingContextFile("src/ConsumerTest.kt", "fun regression() { check(consume() == 43) }\n"),
        ), 0)
        val paths = original.files.mapTo(hashSetOf()) { it.path }
        val anchors = repositoryEvidenceAnchors(original, paths)
        assertEquals(null, repositoryEvidenceRetentionDiagnostic(anchors, original))
        val misleading = original.copy(files = original.files.map { if (it.path == "src/Producer.kt") it.copy(content = "fun unrelated() = 0\n") else it })
        assertEquals(null, repositoryContextAdequacyDiagnostic(misleading))
        assertTrue(requireNotNull(repositoryEvidenceRetentionDiagnostic(anchors, misleading)).contains("src/Producer.kt"))
        assertTrue(requireNotNull(repositoryEvidenceRetentionDiagnostic(anchors, original.copy(files = original.files.dropLast(1)))).contains("ConsumerTest.kt"))
        val bounded = com.orchard.backend.analysis.compactRepositoryContextToBudget(
            original, 500, paths,
            tokenCounter = { if (it.contains("produce() = 42")) 501 else 499 },
            fileContentCompactor = { file, _ -> if (file.path == "src/Producer.kt") "fun unrelated() = 0\n" else file.content },
            contextAdequacy = { repositoryContextAdequacyDiagnostic(it) == null && repositoryEvidenceRetentionDiagnostic(anchors, it) == null },
            promptFor = { contextJson.encodeToString(it) },
        )
        assertEquals(null, bounded)
    }

    @Test
    fun `Kotlin context selects complete behavior beyond a long import preamble`() {
        val source = "package example\n" + (1..200).joinToString("\n") { "import example.Dependency$it" } +
            "\nclass Owner {\n    fun answer(): Int {\n        return 42\n    }\n}\n"
        val excerpt = kotlinContextExcerpt(source, setOf("answer"), 150)

        assertTrue(excerpt.contains("return 42"))
        assertTrue(excerpt.encodeToByteArray().size <= 150)
        val compacted = contextFileExcerpt(CodingContextFile("src/Owner.kt", excerpt, "a".repeat(64)), setOf("answer"), 80)
        assertTrue(compacted.contains("// Orchard source lines 203-205"), compacted)
        assertEquals(null, repositoryContextAdequacyDiagnostic(CodingRepositoryContext(listOf(
            CodingContextFile("src/Owner.kt", excerpt, "a".repeat(64)),
        ), 0)))
    }

    @Test
    fun `Kotlin context rejects headers and partial behavior but allows complete scaffold source`() {
        val headers = CodingContextFile("src/Owner.kt", "package example\nimport example.Owner\nclass Owner {", "a".repeat(64))
        assertTrue(requireNotNull(repositoryContextAdequacyDiagnostic(CodingRepositoryContext(listOf(headers), 0))).startsWith("SOURCE_EVIDENCE_INADEQUATE"))
        assertTrue(kotlinContextExcerpt("fun answer(): Int {\n" + "    println(42)\n".repeat(100) + "    return 42\n}", setOf("answer"), 64).isEmpty())
        assertEquals(null, repositoryContextAdequacyDiagnostic(CodingRepositoryContext(listOf(
            CodingContextFile("src/Scaffold.kt", "class Scaffold\n"),
        ), 0)))
        assertEquals(null, repositoryContextAdequacyDiagnostic(CodingRepositoryContext(listOf(
            CodingContextFile("src/.gitkeep", ""),
        ), 0)))
        assertTrue(requireNotNull(repositoryContextAdequacyDiagnostic(CodingRepositoryContext(listOf(
            CodingContextFile("src/Owner.kt", "", "a".repeat(64)),
        ), 0))).startsWith("SOURCE_EVIDENCE_EMPTY"))
    }

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
    fun `analysis context pins exact repository scope paths before ranked distractors`() {
        val repository = createTempDirectory("orchard-scope-anchor-context-")
        git(repository, "init")
        Files.createDirectories(repository.resolve("src"))
        repeat(110) { index ->
            Files.writeString(
                repository.resolve("src/AAADistractor$index.kt"),
                "fun distractingRepositoryAnalysis$index() = Unit\n",
            )
        }
        val ownerPath = "src/ZScopedOwner.kt"
        Files.writeString(repository.resolve(ownerPath), "class ScopedOwner\n")
        git(repository, "add", ".")

        val selectors = compileScopePathEvidenceSelectors(
            listOf("Modify `$ownerPath` to compile accepted Attention anchors."),
            emptyList(),
        )
        val context = LocalCodingWorkspaceGateway().collectAnalysisContext(
            repository.toString(),
            "distracting repository analysis",
            selectors,
        )

        val anchor = context.files.single { it.path == ownerPath }
        assertEquals(listOf("scope-path-0-0"), anchor.matchedEvidenceSelectorIds)
        assertTrue(anchor.contentHash.matches(Regex("[0-9a-f]{64}")))
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

    @Test
    fun `plan context preserves small files before excerpting large evidence files`() {
        val budgets = planContextFileBudgets(
            sourceBytes = listOf(138_022, 59_886, 79_317, 60_007, 9_182),
            totalBytes = 30_000,
            minimumBytes = 128,
        )

        assertEquals(9_182, budgets[4])
        assertEquals(30_000, budgets.sum())
        assertTrue(budgets.take(4).all { it >= 128 })
    }

    @Test
    fun `intelligence context reads complete source beyond prompt excerpt limit`() {
        val repository = createTempDirectory("orchard-intelligence-context-")
        git(repository, "init")
        Files.createDirectories(repository.resolve("src"))
        val content = "val typography = FontFamily.Default\n".repeat(2_500)
        Files.writeString(repository.resolve("src/LargeTheme.kt"), content)
        git(repository, "add", ".")
        git(repository, "-c", "user.name=Orchard Test", "-c", "user.email=orchard@example.test", "commit", "-m", "Initial")
        val revision = gitOutput(repository, "rev-parse", "HEAD")

        val context = LocalCodingWorkspaceGateway().collectIntelligenceContext(
            repository.toString(), revision, listOf("src/LargeTheme.kt"),
        )

        assertEquals(0, context.omittedFileCount)
        assertEquals(content, context.files.single().content)
        assertTrue(content.encodeToByteArray().size > 64 * 1024)
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