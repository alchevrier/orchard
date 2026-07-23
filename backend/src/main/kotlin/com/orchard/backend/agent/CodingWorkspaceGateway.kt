package com.orchard.backend.agent

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Comparator
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable

const val CODING_FILE_WRITE = "WRITE"
const val CODING_FILE_DELETE = "DELETE"
const val CODING_FILE_REPLACE = "REPLACE"

@Serializable
data class CodingContextFile(
    val path: String,
    val content: String,
    val contentHash: String = sha256Content(content),
    val matchedDeclarations: List<String> = emptyList(),
    val containsExplicitFontFamily: Boolean = false,
)

@Serializable
data class CodingRepositoryContext(
    val files: List<CodingContextFile>,
    val omittedFileCount: Int,
)

@Serializable
data class CodingFileOperation(
    val action: String,
    val path: String,
    val content: String? = null,
    val replacements: List<CodingTextReplacement> = emptyList(),
)

@Serializable
data class CodingTextReplacement(
    val old: String,
    val new: String,
)

@Serializable
data class CodingPatchProposal(
    val summary: String,
    val operations: List<CodingFileOperation>,
)

data class CodingCandidate(
    val revision: String,
    val changedPaths: List<String>,
)

data class VerificationObservation(
    val command: String,
    val exitCode: Int,
    val outputHash: String,
    val summary: String,
)

interface CodingWorkspaceGateway {
    fun collectContext(workspacePath: String, query: String): CodingRepositoryContext
    fun collectAnalysisContext(workspacePath: String, query: String): CodingRepositoryContext = collectContext(workspacePath, query)
    fun collectGenesisContext(workspacePath: String, query: String): CodingRepositoryContext = collectContext(workspacePath, query)
    fun collectIntelligenceContext(workspacePath: String, repositoryRevision: String, paths: List<String>): CodingRepositoryContext =
        collectAnalysisContext(workspacePath, paths.joinToString(" "))
    fun currentRevision(workspacePath: String): String? = null
    fun applyAndCommit(workspacePath: String, proposal: CodingPatchProposal, executionId: Long): CodingCandidate
    fun resolveToolchainPolicy(workspacePath: String): ResolvedToolchainPolicy?
    fun parseVerificationCommand(command: String): VerificationCommand
    fun executeVerification(
        workspacePath: String,
        command: VerificationCommand,
        evidenceCommand: String = command.canonical(),
    ): VerificationObservation
}

class LocalCodingWorkspaceGateway(
    private val policyCatalog: ToolchainPolicyCatalog = FileToolchainPolicyCatalog(),
) : CodingWorkspaceGateway {
    override fun collectContext(workspacePath: String, query: String): CodingRepositoryContext =
        collectContext(workspacePath, query, MAX_CONTEXT_FILES, MAX_CONTEXT_BYTES, MAX_CONTEXT_FILE_BYTES)

    override fun collectAnalysisContext(workspacePath: String, query: String): CodingRepositoryContext =
        collectContext(workspacePath, query, MAX_ANALYSIS_CONTEXT_FILES, MAX_ANALYSIS_CONTEXT_BYTES, MAX_ANALYSIS_CONTEXT_FILE_BYTES)

    override fun collectGenesisContext(workspacePath: String, query: String): CodingRepositoryContext =
        collectContext(
            workspacePath,
            query,
            MAX_GENESIS_CONTEXT_FILES,
            MAX_GENESIS_CONTEXT_BYTES,
            MAX_GENESIS_CONTEXT_BYTES,
            includePath = ::isGenesisImplementationPath,
        )

    override fun collectIntelligenceContext(
        workspacePath: String,
        repositoryRevision: String,
        paths: List<String>,
    ): CodingRepositoryContext {
        val root = validatedRoot(workspacePath)
        requireGitWorkspace(root)
        require(repositoryRevision.matches(GIT_HASH)) { "Repository intelligence revision is invalid" }
        val selected = mutableListOf<CodingContextFile>()
        var bytesUsed = 0
        paths.distinct().forEach { relative ->
            runCatching { validatedRelative(root, relative, mustExist = false) }.getOrNull() ?: return@forEach
            val bytes = runCatching { readGitBlob(root, repositoryRevision, relative) }.getOrNull() ?: return@forEach
            if (bytes.any { it == 0.toByte() }) return@forEach
            if (selected.size < MAX_ANALYSIS_CONTEXT_FILES && bytesUsed + bytes.size <= MAX_ANALYSIS_CONTEXT_BYTES) {
                val content = bytes.toString(Charsets.UTF_8)
                selected += CodingContextFile(relative, content)
                bytesUsed += bytes.size
            }
        }
        return CodingRepositoryContext(selected, (paths.distinct().size - selected.size).coerceAtLeast(0))
    }

    private fun readGitBlob(root: Path, revision: String, relative: String): ByteArray? {
        val outputPath = Files.createTempFile("orchard-intelligence-context-", ".blob")
        val errorPath = Files.createTempFile("orchard-intelligence-context-", ".log")
        try {
            val process = ProcessBuilder("git", "-C", root.toString(), "show", "$revision:$relative")
                .redirectOutput(outputPath.toFile())
                .redirectError(errorPath.toFile())
                .start()
            if (!process.waitFor(CONTEXT_COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw IllegalStateException("Git repository context read timed out")
            }
            require(process.exitValue() == 0) {
                "Git repository context read failed: ${Files.readString(errorPath).take(512)}"
            }
            if (Files.size(outputPath) > MAX_CONTEXT_FILE_BYTES) return null
            return Files.readAllBytes(outputPath)
        } finally {
            Files.deleteIfExists(outputPath)
            Files.deleteIfExists(errorPath)
        }
    }

    override fun currentRevision(workspacePath: String): String? {
        val root = validatedRoot(workspacePath)
        requireGitWorkspace(root)
        return run(root, listOf("git", "rev-parse", "--verify", "HEAD"), GIT_COMMAND_TIMEOUT_SECONDS)
            .takeIf { it.exitCode == 0 && it.output.matches(GIT_HASH) }
            ?.output
    }

    private fun collectContext(
        workspacePath: String,
        query: String,
        maxFiles: Int,
        maxBytes: Int,
        maxFileBytes: Int,
        includePath: (String) -> Boolean = { true },
    ): CodingRepositoryContext {
        val root = validatedRoot(workspacePath)
        requireGitWorkspace(root)
        val tracked = run(root, listOf("git", "ls-files"), CONTEXT_COMMAND_TIMEOUT_SECONDS).output
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .filter(includePath)
            .mapNotNull { relative -> runCatching { validatedRelative(root, relative, mustExist = true) }.getOrNull() }
            .filter { Files.isRegularFile(it) && !Files.isSymbolicLink(it) }
            .toList()
        val queryTokens = tokens(query)
        val ranked = tracked.mapNotNull { path ->
            val size = runCatching { Files.size(path) }.getOrNull() ?: return@mapNotNull null
            if (size > MAX_CONTEXT_SOURCE_BYTES) return@mapNotNull null
            val bytes = runCatching { Files.readAllBytes(path) }.getOrNull() ?: return@mapNotNull null
            if (bytes.any { it == 0.toByte() }) return@mapNotNull null
            val source = bytes.toString(Charsets.UTF_8)
            val content = focusedContextExcerpt(source, queryTokens, maxFileBytes)
            val relative = root.relativize(path).toString().replace('\\', '/')
            val lowerPath = relative.lowercase()
            val lowerContent = source.lowercase()
            val score = queryTokens.sumOf { token ->
                (if (lowerPath.contains(token)) 20 else 0) + (if (lowerContent.contains(token)) 1 else 0)
            } + foundationScore(relative)
            RankedContextFile(
                score,
                relative,
                CodingContextFile(
                    relative,
                    content,
                    sha256Content(source),
                    matchedSourceDeclarations(source, queryTokens),
                    EXPLICIT_FONT_FAMILY.containsMatchIn(source),
                ),
            )
        }.sortedWith(compareByDescending<RankedContextFile> { it.score }.thenBy { it.path })

        var bytesUsed = 0
        val selected = mutableListOf<CodingContextFile>()
        ranked.forEach { rankedFile ->
            val bytes = rankedFile.file.content.encodeToByteArray().size +
                rankedFile.file.matchedDeclarations.sumOf { it.encodeToByteArray().size }
            if (selected.size < maxFiles && bytesUsed + bytes <= maxBytes) {
                selected += rankedFile.file
                bytesUsed += bytes
            }
        }
        return CodingRepositoryContext(selected, (tracked.size - selected.size).coerceAtLeast(0))
    }

    private fun isGenesisImplementationPath(path: String): Boolean = path
        .replace('\\', '/')
        .split('/')
        .map(String::lowercase)
        .none { segment ->
            segment == "docs" || segment == "doc" || segment == "examples" || segment == "example" ||
                segment == "samples" || segment == "sample" || segment.contains("sandbox") ||
                segment.contains("benchmark") || segment == "test" || segment.endsWith("test") ||
                segment == "tests" || segment.endsWith("tests")
        }

    override fun applyAndCommit(
        workspacePath: String,
        proposal: CodingPatchProposal,
        executionId: Long,
    ): CodingCandidate {
        val root = validatedRoot(workspacePath)
        requireGitWorkspace(root)
        val initialStatus = run(root, listOf("git", "status", "--porcelain"), GIT_COMMAND_TIMEOUT_SECONDS)
        require(initialStatus.exitCode == 0 && initialStatus.output.isBlank()) {
            "Coding workspace must be clean before applying a proposal"
        }
        require(proposal.summary.isNotBlank() && proposal.summary.length <= MAX_SUMMARY_LENGTH) {
            "Coding proposal summary is invalid"
        }
        require(proposal.operations.isNotEmpty() && proposal.operations.size <= MAX_OPERATIONS) {
            "Coding proposal operation count is invalid"
        }
        val normalized = proposal.operations.map { operation ->
            val target = validatedRelative(
                root,
                operation.path,
                mustExist = operation.action in setOf(CODING_FILE_DELETE, CODING_FILE_REPLACE),
            )
            val relative = root.relativize(target).toString().replace('\\', '/')
            operation.copy(path = relative) to target
        }
        require(normalized.map { it.first.path }.distinct().size == normalized.size) {
            "Coding proposal contains duplicate paths"
        }
        val totalBytes = normalized.sumOf { (operation, _) ->
            (operation.content?.encodeToByteArray()?.size ?: 0) + operation.replacements.sumOf { replacement ->
                replacement.old.encodeToByteArray().size + replacement.new.encodeToByteArray().size
            }
        }
        require(totalBytes <= MAX_PATCH_BYTES) { "Coding proposal exceeds the patch byte limit" }
        normalized.forEach { (operation, target) ->
            require(operation.action in setOf(CODING_FILE_WRITE, CODING_FILE_DELETE, CODING_FILE_REPLACE)) {
                "Unsupported coding file operation ${operation.action}"
            }
            when (operation.action) {
                CODING_FILE_WRITE -> {
                    require(operation.content != null && operation.replacements.isEmpty()) {
                        "WRITE operation requires content without replacements"
                    }
                    require(!Files.exists(target) || Files.isRegularFile(target) && !Files.isSymbolicLink(target)) {
                        "WRITE target must be a regular file"
                    }
                }
                CODING_FILE_REPLACE -> {
                    require(operation.content == null && operation.replacements.size in 1..MAX_REPLACEMENTS) {
                        "REPLACE operation requires bounded replacements without complete content"
                    }
                    require(Files.isRegularFile(target) && !Files.isSymbolicLink(target)) {
                        "REPLACE target must be an existing regular file"
                    }
                    var candidate = Files.readString(target, Charsets.UTF_8)
                    operation.replacements.forEach { replacement ->
                        require(replacement.old.isNotEmpty() && candidate.indexOf(replacement.old) == candidate.lastIndexOf(replacement.old)) {
                            "REPLACE old text must occur exactly once"
                        }
                        candidate = candidate.replaceFirst(replacement.old, replacement.new)
                    }
                }
                else -> require(operation.content == null && operation.replacements.isEmpty() && Files.isRegularFile(target) && !Files.isSymbolicLink(target)) {
                    "DELETE target must be an existing regular file without content or replacements"
                }
            }
        }
        val originals = normalized.associate { (_, target) ->
            target to if (Files.exists(target)) Files.readAllBytes(target) else null
        }
        try {
            normalized.forEach { (operation, originalTarget) ->
                val target = validatedRelative(
                    root,
                    operation.path,
                    mustExist = operation.action in setOf(CODING_FILE_DELETE, CODING_FILE_REPLACE),
                )
                require(target == originalTarget) { "Coding path changed before mutation" }
                when (operation.action) {
                    CODING_FILE_WRITE -> writeAtomically(target, requireNotNull(operation.content).toByteArray(Charsets.UTF_8))
                    CODING_FILE_REPLACE -> {
                        var content = Files.readString(target, Charsets.UTF_8)
                        operation.replacements.forEach { replacement ->
                            content = content.replaceFirst(replacement.old, replacement.new)
                        }
                        writeAtomically(target, content.toByteArray(Charsets.UTF_8))
                    }
                    CODING_FILE_DELETE -> Files.delete(target)
                }
            }
            val diffCheck = run(root, listOf("git", "diff", "--check"), GIT_COMMAND_TIMEOUT_SECONDS)
            require(diffCheck.exitCode == 0) { "Candidate patch failed git diff --check: ${diffCheck.output.take(1_000)}" }
            val paths = normalized.map { it.first.path }.sorted()
            val add = run(root, listOf("git", "add", "--all", "--") + paths, GIT_COMMAND_TIMEOUT_SECONDS)
            require(add.exitCode == 0) { "Unable to stage candidate patch: ${add.output.take(1_000)}" }
            val staged = run(root, listOf("git", "diff", "--cached", "--quiet"), GIT_COMMAND_TIMEOUT_SECONDS)
            require(staged.exitCode == 1) { "Coding proposal did not change the repository" }
            val commit = run(
                root,
                listOf(
                    "git", "-c", "user.name=Orchard Coding Worker",
                    "-c", "user.email=orchard-worker@localhost",
                    "commit", "-m", "Orchard coding execution $executionId",
                ),
                GIT_COMMAND_TIMEOUT_SECONDS,
            )
            require(commit.exitCode == 0) { "Unable to commit candidate patch: ${commit.output.take(1_000)}" }
            val revision = run(root, listOf("git", "rev-parse", "--verify", "HEAD"), GIT_COMMAND_TIMEOUT_SECONDS)
            require(revision.exitCode == 0 && revision.output.matches(GIT_HASH)) {
                "Unable to resolve committed candidate revision"
            }
            return CodingCandidate(revision.output, paths)
        } catch (exception: Exception) {
            rollback(originals)
            runCatching {
                run(root, listOf("git", "add", "--all", "--") + originals.keys.map {
                    root.relativize(it).toString().replace('\\', '/')
                }, GIT_COMMAND_TIMEOUT_SECONDS)
            }
            throw exception
        }
    }

    override fun resolveToolchainPolicy(workspacePath: String): ResolvedToolchainPolicy? {
        val root = validatedRoot(workspacePath)
        requireGitWorkspace(root)
        return policyCatalog.resolve(root)
    }

    override fun parseVerificationCommand(command: String): VerificationCommand {
        require(command.isNotBlank() && command.length <= MAX_COMMAND_LENGTH) { "Verification command is invalid" }
        require(command.none { it in ADMITTED_COMMAND_META }) {
            "Shell operators, quoting, and escaping are not permitted in admitted verification commands"
        }
        val arguments = command.trim().split(Regex("\\s+")).filter(String::isNotBlank)
        require(arguments.isNotEmpty() && arguments.size <= MAX_COMMAND_ARGUMENTS) { "Verification command is invalid" }
        return VerificationCommand(arguments.first(), arguments.drop(1)).also { parsed ->
            validateCommand(parsed)
            require(parsed.canonical() == command) { "Verification command must use canonical argument spacing" }
        }
    }

    override fun executeVerification(
        workspacePath: String,
        command: VerificationCommand,
        evidenceCommand: String,
    ): VerificationObservation {
        val root = validatedRoot(workspacePath)
        requireGitWorkspace(root)
        validateCommand(command)
        if (command.executable.startsWith("./")) {
            val executable = validatedRelative(root, command.executable.removePrefix("./"), mustExist = true)
            require(Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS) && Files.isExecutable(executable)) {
                "Repository-local verification executable is invalid"
            }
        }
        val result = run(root, listOf(command.executable) + command.arguments, VERIFICATION_TIMEOUT_SECONDS)
        return VerificationObservation(
            command = evidenceCommand,
            exitCode = result.exitCode,
            outputHash = sha256(result.output),
            summary = result.output.ifBlank { "Command exited with ${result.exitCode}." }.take(MAX_OBSERVATION_SUMMARY),
        )
    }

    private fun validatedRoot(workspacePath: String): Path {
        val root = Path.of(workspacePath)
        require(root.isAbsolute && Files.isDirectory(root)) { "Coding workspace must be an absolute directory" }
        require(!Files.isSymbolicLink(root)) { "Coding workspace cannot be a symbolic link" }
        return root.toRealPath()
    }

    private fun requireGitWorkspace(root: Path) {
        val inside = run(root, listOf("git", "rev-parse", "--is-inside-work-tree"), GIT_COMMAND_TIMEOUT_SECONDS)
        require(inside.exitCode == 0 && inside.output == "true") { "Coding workspace is not a Git worktree" }
    }

    private fun validatedRelative(root: Path, rawPath: String, mustExist: Boolean): Path {
        require(rawPath.isNotBlank() && rawPath.length <= MAX_PATH_LENGTH) { "Coding path is invalid" }
        val relative = Path.of(rawPath)
        require(!relative.isAbsolute && relative.none { it.toString() == ".." }) { "Coding path must remain relative" }
        val normalized = root.resolve(relative).normalize()
        require(normalized.startsWith(root) && normalized != root) { "Coding path escapes the reserved worktree" }
        require(root.relativize(normalized).firstOrNull()?.toString() !in FORBIDDEN_ROOTS) {
            "Coding path targets reserved metadata"
        }
        var current = normalized.parent
        while (current != null && current.startsWith(root) && current != root) {
            require(!Files.isSymbolicLink(current)) { "Coding path traverses a symbolic link" }
            current = current.parent
        }
        if (mustExist) require(Files.exists(normalized)) { "Coding path does not exist" }
        return normalized
    }

    private fun writeAtomically(path: Path, bytes: ByteArray) {
        Files.createDirectories(path.parent)
        val temporary = Files.createTempFile(path.parent, ".orchard-candidate-", ".tmp")
        try {
            FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
            FileChannel.open(path.parent, StandardOpenOption.READ).use { it.force(true) }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun rollback(originals: Map<Path, ByteArray?>) {
        originals.forEach { (path, bytes) ->
            if (bytes == null) {
                Files.deleteIfExists(path)
                var parent = path.parent
                while (parent != null && Files.isDirectory(parent) && Files.list(parent).use { !it.findAny().isPresent }) {
                    Files.delete(parent)
                    parent = parent.parent
                }
            } else {
                writeAtomically(path, bytes)
            }
        }
    }

    private fun run(root: Path, arguments: List<String>, timeoutSeconds: Long): ProcessResult {
        val builder = ProcessBuilder(arguments)
            .directory(root.toFile())
            .redirectErrorStream(true)
        val inherited = System.getenv()
        builder.environment().clear()
        SAFE_ENVIRONMENT.forEach { name -> inherited[name]?.let { builder.environment()[name] = it } }
        builder.environment()["CI"] = "true"
        builder.environment()["GIT_TERMINAL_PROMPT"] = "0"
        val process = builder.start()
        val output = ByteArrayOutputStream(MAX_COMMAND_OUTPUT_BYTES)
        var truncated = false
        val outputReader = Thread({
            process.inputStream.use { input ->
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    val remaining = MAX_COMMAND_OUTPUT_BYTES - output.size()
                    if (remaining > 0) output.write(buffer, 0, minOf(count, remaining))
                    if (count > remaining) truncated = true
                }
            }
        }, "orchard-command-output").apply {
            isDaemon = true
            start()
        }
        val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        val suffix = if (completed) "" else "Command timed out after $timeoutSeconds seconds."
        if (!completed) {
            process.descendants().sorted(Comparator.reverseOrder()).forEach { it.destroyForcibly() }
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
        outputReader.join(5_000)
        if (outputReader.isAlive) {
            process.inputStream.close()
            outputReader.join(1_000)
        }
        check(!outputReader.isAlive) { "Verification output capture did not terminate" }
        return ProcessResult(if (completed) process.exitValue() else 124, boundedOutput(output.toByteArray(), truncated, suffix))
    }

    private fun boundedOutput(bytes: ByteArray, truncated: Boolean, suffix: String): String {
        return buildString {
            append(bytes.toString(Charsets.UTF_8).trim())
            if (truncated) append("\n[output truncated]")
            if (suffix.isNotBlank()) append("\n").append(suffix)
        }.trim()
    }

    private fun foundationScore(path: String): Int {
        if (path in DOCUMENTATION_INDEX_FILES) return 20
        val name = path.substringAfterLast('/')
        return if (name in FOUNDATION_FILES || path.startsWith("docs/")) 10 else 0
    }

    private fun tokens(value: String): Set<String> = value.lowercase()
        .split(Regex("[^a-z0-9_]+"))
        .filter { it.length >= 3 }
        .toSet()

    private fun sha256(value: String): String = sha256Content(value)

    private data class ProcessResult(val exitCode: Int, val output: String)

    private data class RankedContextFile(
        val score: Int,
        val path: String,
        val file: CodingContextFile,
    )

    private companion object {
        const val MAX_REPLACEMENTS = 32
        const val MAX_CONTEXT_FILES = 32
        const val MAX_CONTEXT_FILE_BYTES = 64 * 1024
        const val MAX_CONTEXT_SOURCE_BYTES = 1024 * 1024L
        const val MAX_CONTEXT_BYTES = 256 * 1024
        const val MAX_ANALYSIS_CONTEXT_FILES = 96
        const val MAX_ANALYSIS_CONTEXT_BYTES = 768 * 1024
        const val MAX_ANALYSIS_CONTEXT_FILE_BYTES = 12 * 1024
        const val MAX_GENESIS_CONTEXT_FILES = 6
        const val MAX_GENESIS_CONTEXT_BYTES = 4 * 1024
        const val MAX_OPERATIONS = 32
        const val MAX_PATCH_BYTES = 512 * 1024
        const val MAX_PATH_LENGTH = 512
        const val MAX_SUMMARY_LENGTH = 2_000
        const val MAX_COMMAND_LENGTH = 1_024
        const val MAX_COMMAND_ARGUMENTS = 64
        const val MAX_COMMAND_OUTPUT_BYTES = 256 * 1024
        const val MAX_OBSERVATION_SUMMARY = 4_096
        const val CONTEXT_COMMAND_TIMEOUT_SECONDS = 10L
        const val GIT_COMMAND_TIMEOUT_SECONDS = 30L
        const val VERIFICATION_TIMEOUT_SECONDS = 300L
        val GIT_HASH = Regex("[0-9a-fA-F]{40,64}")
        val SHELL_META = setOf('|', '&', ';', '>', '<', '`', '\n', '\r')
        val ADMITTED_COMMAND_META = SHELL_META + setOf('\'', '"', '\\')
        val FORBIDDEN_ROOTS = setOf(".git", ".orchard")
        val SAFE_ENVIRONMENT = setOf(
            "PATH", "HOME", "JAVA_HOME", "GRADLE_USER_HOME", "MAVEN_OPTS", "CARGO_HOME", "RUSTUP_HOME", "NPM_CONFIG_CACHE"
        )
        val FOUNDATION_FILES = setOf(
            "README.md", "ROADMAP.md", "build.gradle.kts", "build.gradle", "settings.gradle.kts", "settings.gradle",
            "pom.xml", "Cargo.toml", "meson.build", "CMakeLists.txt", "package.json", "AGENTS.md"
        )
        val DOCUMENTATION_INDEX_FILES = setOf(
            "docs/README.md", "docs/user-guide/README.md", "docs/developer/README.md"
        )
    }
}

internal fun focusedContextExcerpt(content: String, queryTokens: Set<String>, maxBytes: Int): String {
    require(maxBytes > 0)
    if (content.encodeToByteArray().size <= maxBytes) return content
    val lines = content.split('\n')
    val matches = lines.indices.mapNotNull { index ->
        val lower = lines[index].lowercase()
        val matchedTokens = queryTokens.filterTo(mutableSetOf(), lower::contains)
        matchedTokens.size.takeIf { it > 0 }?.let { tokenScore ->
            ExcerptMatch(
                index,
                tokenScore + if (SOURCE_DECLARATION.containsMatchIn(lower)) DECLARATION_MATCH_BONUS else 0,
                matchedTokens,
                SOURCE_DECLARATION.containsMatchIn(lower),
            )
        }
    }
    val declarationMatches = matches.filter(ExcerptMatch::declaration)
    val declarationTokenFrequency = declarationMatches
        .flatMap(ExcerptMatch::matchedTokens)
        .groupingBy(String::lowercase)
        .eachCount()
    val reservedDeclarations = declarationTokenFrequency.keys
        .sortedWith(compareBy<String> { declarationTokenFrequency.getValue(it) }.thenBy(String::lowercase))
        .mapNotNull { token ->
            declarationMatches.filter { token in it.matchedTokens }
                .maxWithOrNull(compareBy<ExcerptMatch> { it.score }.thenByDescending { it.index })
        }
        .distinctBy(ExcerptMatch::index)
    val rankedMatches = (reservedDeclarations + matches.sortedWith(
        compareByDescending<ExcerptMatch> { it.score }.thenBy { it.index }
    )).distinctBy(ExcerptMatch::index)
        .take(MAX_EXCERPT_WINDOWS)
    var selectedBytes = 0
    val windows = rankedMatches.map { match ->
        (match.index - EXCERPT_CONTEXT_LINES).coerceAtLeast(0)..(match.index + EXCERPT_CONTEXT_LINES).coerceAtMost(lines.lastIndex)
    }.fold(mutableListOf<IntRange>()) { selected, window ->
        if (selected.none { existing -> window.first <= existing.last && existing.first <= window.last }) {
            val sectionBytes = excerptSection(lines, window).encodeToByteArray().size
            if (selectedBytes + sectionBytes <= maxBytes) {
                selected += window
                selectedBytes += sectionBytes
            }
        }
        selected
    }.ifEmpty { mutableListOf(0..minOf(lines.lastIndex, EXCERPT_CONTEXT_LINES * 2)) }
    val excerpt = StringBuilder()
    windows.sortedBy { it.first }.forEach { window -> excerpt.append(excerptSection(lines, window)) }
    return excerpt.toString().ifBlank {
        lines.asSequence().runningFold("") { result, line -> "$result$line\n" }
            .takeWhile { it.encodeToByteArray().size <= maxBytes }
            .lastOrNull().orEmpty()
    }
}

internal fun matchedSourceDeclarations(content: String, queryTokens: Set<String>): List<String> {
    val matches = content.lineSequence().mapIndexedNotNull { index, line ->
        val lower = line.lowercase()
        if (!SOURCE_DECLARATION.containsMatchIn(lower)) return@mapIndexedNotNull null
        val matchedTokens = queryTokens.filterTo(mutableSetOf(), lower::contains)
        matchedTokens.size.takeIf { it > 0 }?.let {
            SourceDeclarationMatch(index, line.trim().take(MAX_DECLARATION_CHARS), matchedTokens)
        }
    }.toList()
    val tokenFrequency = matches.flatMap(SourceDeclarationMatch::matchedTokens)
        .groupingBy(String::lowercase)
        .eachCount()
    val reserved = tokenFrequency.keys
        .sortedWith(compareBy<String> { tokenFrequency.getValue(it) }.thenBy(String::lowercase))
        .mapNotNull { token ->
            matches.filter { token in it.matchedTokens }
                .maxWithOrNull(compareBy<SourceDeclarationMatch> { it.matchedTokens.size }.thenByDescending { it.index })
        }
    return (reserved + matches.sortedWith(
        compareByDescending<SourceDeclarationMatch> { it.matchedTokens.size }.thenBy { it.index }
    )).distinctBy(SourceDeclarationMatch::index)
        .take(MAX_MATCHED_DECLARATIONS)
        .sortedBy(SourceDeclarationMatch::index)
        .map(SourceDeclarationMatch::line)
}

private fun excerptSection(lines: List<String>, window: IntRange): String = buildString {
    append("[Orchard excerpt lines ${window.first + 1}-${window.last + 1} of ${lines.size}]\n")
    window.forEach { index -> append(lines[index]).append('\n') }
}

private const val MAX_EXCERPT_WINDOWS = 64
private const val EXCERPT_CONTEXT_LINES = 3
private const val DECLARATION_MATCH_BONUS = 2
private const val MAX_MATCHED_DECLARATIONS = 64
private const val MAX_DECLARATION_CHARS = 512
private val SOURCE_DECLARATION = Regex("\\b(class|interface|object|fun|val|var|typealias)\\b")
private val EXPLICIT_FONT_FAMILY = Regex("\\bFontFamily\\s*\\.")
private data class ExcerptMatch(
    val index: Int,
    val score: Int,
    val matchedTokens: Set<String>,
    val declaration: Boolean,
)
private data class SourceDeclarationMatch(
    val index: Int,
    val line: String,
    val matchedTokens: Set<String>,
)

internal fun sha256Content(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
