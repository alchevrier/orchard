package com.orchard.backend.agent

import com.orchard.backend.workspace.REPOSITORY_EVIDENCE_MATCH_ALL
import com.orchard.backend.workspace.RepositoryEvidenceSelector
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileSystems
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Comparator
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val CODING_FILE_WRITE = "WRITE"
const val CODING_FILE_DELETE = "DELETE"
const val CODING_FILE_REPLACE = "REPLACE"
private const val MAX_REJECTED_ANCHOR_PREVIEW_CHARS = 512

@Serializable
data class CodingContextFile(
    val path: String,
    val content: String,
    val contentHash: String = sha256Content(content),
    val matchedDeclarations: List<String> = emptyList(),
    val matchedEvidenceSelectorIds: List<String> = emptyList(),
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
    fun collectAnalysisContext(
        workspacePath: String,
        query: String,
        selectors: List<RepositoryEvidenceSelector>,
    ): CodingRepositoryContext = collectAnalysisContext(workspacePath, query)
    fun collectGenesisContext(workspacePath: String, query: String): CodingRepositoryContext = collectContext(workspacePath, query)
    fun collectPlanContext(
        workspacePath: String,
        repositoryRevision: String,
        paths: List<String>,
        query: String,
    ): CodingRepositoryContext = collectIntelligenceContext(workspacePath, repositoryRevision, paths)
    fun collectPlanContext(
        workspacePath: String,
        repositoryRevision: String,
        paths: List<String>,
        query: String,
        maxSerializedBytes: Int,
    ): CodingRepositoryContext = collectPlanContext(workspacePath, repositoryRevision, paths, query)
    fun collectIntelligenceContext(workspacePath: String, repositoryRevision: String, paths: List<String>): CodingRepositoryContext =
        collectAnalysisContext(workspacePath, paths.joinToString(" "))
    fun currentRevision(workspacePath: String): String? = null
    fun treeMatches(workspacePath: String, firstRevision: String, secondRevision: String): Boolean = false
    fun revisionCompatible(
        workspacePath: String,
        baseRevision: String,
        currentRevision: String,
        relevantPaths: List<String>,
    ): Boolean = false
    fun applyAndCommit(workspacePath: String, proposal: CodingPatchProposal, executionId: Long): CodingCandidate
    fun revertCandidate(workspacePath: String, candidateRevision: String, executionId: Long): String? = null
    fun restoreTree(workspacePath: String, expectedRevision: String, baseRevision: String, runId: Long): String? = null
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
    private val contextLocks = ConcurrentHashMap<String, Any>()

    override fun collectContext(workspacePath: String, query: String): CodingRepositoryContext =
        collectContext(workspacePath, query, MAX_CONTEXT_FILES, MAX_CONTEXT_BYTES, MAX_CONTEXT_FILE_BYTES)

    override fun collectAnalysisContext(workspacePath: String, query: String): CodingRepositoryContext =
        collectContext(workspacePath, query, MAX_ANALYSIS_CONTEXT_FILES, MAX_ANALYSIS_CONTEXT_BYTES, MAX_ANALYSIS_CONTEXT_FILE_BYTES)

    override fun collectAnalysisContext(
        workspacePath: String,
        query: String,
        selectors: List<RepositoryEvidenceSelector>,
    ): CodingRepositoryContext = collectContext(
        workspacePath,
        query,
        MAX_ANALYSIS_CONTEXT_FILES,
        MAX_ANALYSIS_CONTEXT_BYTES,
        MAX_ANALYSIS_CONTEXT_FILE_BYTES,
        selectors = selectors,
    )

    override fun collectGenesisContext(workspacePath: String, query: String): CodingRepositoryContext =
        collectContext(
            workspacePath,
            query,
            MAX_GENESIS_CONTEXT_FILES,
            MAX_GENESIS_CONTEXT_BYTES,
            MAX_GENESIS_CONTEXT_BYTES,
            includePath = ::isGenesisImplementationPath,
        )

    override fun collectPlanContext(
        workspacePath: String,
        repositoryRevision: String,
        paths: List<String>,
        query: String,
    ): CodingRepositoryContext = collectPlanContext(
        workspacePath,
        repositoryRevision,
        paths,
        query,
        MAX_CONTEXT_BYTES,
    )

    override fun collectPlanContext(
        workspacePath: String,
        repositoryRevision: String,
        paths: List<String>,
        query: String,
        maxSerializedBytes: Int,
    ): CodingRepositoryContext {
        val root = validatedRoot(workspacePath)
        requireGitWorkspace(root)
        require(repositoryRevision.matches(GIT_HASH)) { "Repository plan revision is invalid" }
        val distinctPaths = paths.distinct()
        require(distinctPaths.isNotEmpty() && distinctPaths.size <= MAX_CONTEXT_FILES) {
            "Repository plan path count is invalid"
        }
        require(maxSerializedBytes > 0) { "Repository plan context budget is invalid" }
        val queryTokens = tokens(query)
        val sources = distinctPaths.mapNotNull { relative ->
            runCatching { validatedRelative(root, relative, mustExist = false) }.getOrNull() ?: return@mapNotNull null
            val bytes = runCatching {
                readGitBlob(root, repositoryRevision, relative, MAX_CONTEXT_SOURCE_BYTES)
            }.getOrNull() ?: return@mapNotNull null
            if (bytes.any { it == 0.toByte() }) return@mapNotNull null
            relative to bytes.toString(Charsets.UTF_8)
        }
        if (sources.size != distinctPaths.size) {
            return CodingRepositoryContext(emptyList(), distinctPaths.size - sources.size)
        }
        fun context(totalContentBytes: Int): CodingRepositoryContext {
            val budgets = planContextFileBudgets(
                sources.map { (_, source) -> minOf(source.encodeToByteArray().size, MAX_CONTEXT_FILE_BYTES) },
                totalContentBytes,
                MIN_PLAN_CONTEXT_FILE_BYTES,
            )
            return CodingRepositoryContext(
            files = sources.mapIndexed { index, (relative, source) ->
                val content = focusedContextExcerpt(source, queryTokens, budgets[index])
                CodingContextFile(
                    path = relative,
                    content = content,
                    contentHash = sha256Content(source),
                    matchedDeclarations = matchedSourceDeclarations(content, queryTokens),
                )
            },
            omittedFileCount = 0,
        )
        }
        var totalContentBytes = minOf(MAX_CONTEXT_FILE_BYTES * distinctPaths.size, maxSerializedBytes)
        require(totalContentBytes >= MIN_PLAN_CONTEXT_FILE_BYTES * distinctPaths.size) {
            "Repository plan context budget is too small"
        }
        var selected = context(totalContentBytes)
        var serializedBytes = CONTEXT_JSON.encodeToString(selected).encodeToByteArray().size
        while (serializedBytes > maxSerializedBytes && totalContentBytes > MIN_PLAN_CONTEXT_FILE_BYTES * distinctPaths.size) {
            totalContentBytes = maxOf(
                MIN_PLAN_CONTEXT_FILE_BYTES * distinctPaths.size,
                minOf(totalContentBytes - 1, totalContentBytes * maxSerializedBytes / serializedBytes),
            )
            selected = context(totalContentBytes)
            serializedBytes = CONTEXT_JSON.encodeToString(selected).encodeToByteArray().size
        }
        require(serializedBytes <= maxSerializedBytes && selected.files.all { it.content.isNotEmpty() }) {
            "Repository plan context does not fit the model input budget"
        }
        return selected
    }

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
            val bytes = runCatching { readGitBlob(root, repositoryRevision, relative, MAX_CONTEXT_SOURCE_BYTES) }
                .getOrNull() ?: return@forEach
            if (bytes.any { it == 0.toByte() }) return@forEach
            if (selected.size < MAX_ANALYSIS_CONTEXT_FILES && bytesUsed + bytes.size <= MAX_ANALYSIS_CONTEXT_BYTES) {
                val content = bytes.toString(Charsets.UTF_8)
                selected += CodingContextFile(relative, content)
                bytesUsed += bytes.size
            }
        }
        return CodingRepositoryContext(selected, (paths.distinct().size - selected.size).coerceAtLeast(0))
    }

    private fun readGitBlob(root: Path, revision: String, relative: String, maxBytes: Long): ByteArray? {
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
            if (Files.size(outputPath) > maxBytes) return null
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

    override fun treeMatches(workspacePath: String, firstRevision: String, secondRevision: String): Boolean {
        val root = validatedRoot(workspacePath)
        requireGitWorkspace(root)
        require(firstRevision.matches(GIT_HASH) && secondRevision.matches(GIT_HASH)) {
            "Tree comparison revisions are invalid"
        }
        return run(root, listOf("git", "diff", "--quiet", firstRevision, secondRevision, "--"), GIT_COMMAND_TIMEOUT_SECONDS)
            .exitCode == 0
    }

    override fun revisionCompatible(
        workspacePath: String,
        baseRevision: String,
        currentRevision: String,
        relevantPaths: List<String>,
    ): Boolean {
        val root = validatedRoot(workspacePath)
        requireGitWorkspace(root)
        require(baseRevision.matches(GIT_HASH) && currentRevision.matches(GIT_HASH)) {
            "Revision compatibility hashes are invalid"
        }
        if (relevantPaths.isEmpty()) return false
        val mergeBase = run(root, listOf("git", "merge-base", baseRevision, currentRevision), GIT_COMMAND_TIMEOUT_SECONDS)
        if (mergeBase.exitCode != 0) return false
        val merge = run(root, listOf("git", "merge-tree", "--write-tree", baseRevision, currentRevision), GIT_COMMAND_TIMEOUT_SECONDS)
        if (merge.exitCode != 0) return false
        val arguments = mutableListOf("git", "diff", "--quiet", baseRevision, currentRevision, "--")
        arguments += relevantPaths.distinct()
        return run(root, arguments, GIT_COMMAND_TIMEOUT_SECONDS).exitCode == 0
    }

    override fun revertCandidate(workspacePath: String, candidateRevision: String, executionId: Long): String {
        val root = validatedRoot(workspacePath)
        requireGitWorkspace(root)
        require(candidateRevision.matches(GIT_HASH)) { "Candidate revision is invalid" }
        val status = run(root, listOf("git", "status", "--porcelain"), GIT_COMMAND_TIMEOUT_SECONDS)
        require(status.exitCode == 0 && status.output.isBlank()) {
            "Coding workspace must be clean before reverting a candidate"
        }
        val head = requireNotNull(currentRevision(workspacePath)) { "Unable to resolve coding workspace revision" }
        require(head == candidateRevision) {
            "Coding workspace revision $head does not match failed candidate $candidateRevision"
        }
        val parent = run(root, listOf("git", "rev-parse", "--verify", "$candidateRevision^"), GIT_COMMAND_TIMEOUT_SECONDS)
        require(parent.exitCode == 0 && parent.output.matches(GIT_HASH)) { "Failed candidate has no valid parent revision" }
        val revert = run(
            root,
            listOf(
                "git", "-c", "user.name=Orchard Coding Worker",
                "-c", "user.email=orchard-worker@localhost",
                "revert", "--no-edit", candidateRevision,
            ),
            GIT_COMMAND_TIMEOUT_SECONDS,
        )
        require(revert.exitCode == 0) { "Unable to revert failed candidate: ${revert.output.take(1_000)}" }
        val restoredTree = run(root, listOf("git", "diff", "--quiet", parent.output, "HEAD", "--"), GIT_COMMAND_TIMEOUT_SECONDS)
        require(restoredTree.exitCode == 0) { "Failed candidate revert did not restore its parent tree" }
        val restoredStatus = run(root, listOf("git", "status", "--porcelain"), GIT_COMMAND_TIMEOUT_SECONDS)
        require(restoredStatus.exitCode == 0 && restoredStatus.output.isBlank()) {
            "Coding workspace is not clean after reverting failed candidate for execution $executionId"
        }
        return requireNotNull(currentRevision(workspacePath)) { "Unable to resolve restored coding workspace revision" }
    }

    override fun restoreTree(workspacePath: String, expectedRevision: String, baseRevision: String, runId: Long): String {
        val root = validatedRoot(workspacePath)
        requireGitWorkspace(root)
        require(expectedRevision.matches(GIT_HASH) && baseRevision.matches(GIT_HASH)) { "Workspace restoration revision is invalid" }
        val status = run(root, listOf("git", "status", "--porcelain"), GIT_COMMAND_TIMEOUT_SECONDS)
        require(status.exitCode == 0 && status.output.isBlank()) { "Coding workspace must be clean before tree restoration" }
        require(currentRevision(workspacePath) == expectedRevision) { "Coding workspace changed before tree restoration" }
        val ancestor = run(root, listOf("git", "merge-base", "--is-ancestor", baseRevision, expectedRevision), GIT_COMMAND_TIMEOUT_SECONDS)
        require(ancestor.exitCode == 0) { "Pinned restoration base is not an ancestor of the coding workspace" }
        val changed = run(root, listOf("git", "diff", "--quiet", baseRevision, expectedRevision, "--"), GIT_COMMAND_TIMEOUT_SECONDS)
        if (changed.exitCode == 0) return expectedRevision
        require(changed.exitCode == 1) { "Unable to compare coding workspace with its pinned base" }
        val restore = run(root, listOf("git", "restore", "--source", baseRevision, "--staged", "--worktree", "--", "."), GIT_COMMAND_TIMEOUT_SECONDS)
        require(restore.exitCode == 0) { "Unable to restore coding workspace tree: ${restore.output.take(1_000)}" }
        val commit = run(
            root,
            listOf(
                "git", "-c", "user.name=Orchard Coding Worker",
                "-c", "user.email=orchard-worker@localhost",
                "commit", "-m", "Restore failed candidate chain for run $runId",
            ),
            GIT_COMMAND_TIMEOUT_SECONDS,
        )
        require(commit.exitCode == 0) { "Unable to commit coding workspace restoration: ${commit.output.take(1_000)}" }
        val restored = requireNotNull(currentRevision(workspacePath)) { "Unable to resolve restored coding workspace revision" }
        val restoredTree = run(root, listOf("git", "diff", "--quiet", baseRevision, restored, "--"), GIT_COMMAND_TIMEOUT_SECONDS)
        require(restoredTree.exitCode == 0) { "Coding workspace restoration does not match its pinned base tree" }
        return restored
    }

    private fun collectContext(
        workspacePath: String,
        query: String,
        maxFiles: Int,
        maxBytes: Int,
        maxFileBytes: Int,
        includePath: (String) -> Boolean = { true },
        selectors: List<RepositoryEvidenceSelector> = emptyList(),
    ): CodingRepositoryContext = synchronized(contextLocks.computeIfAbsent(workspacePath) { Any() }) {
        collectContextUncached(workspacePath, query, maxFiles, maxBytes, maxFileBytes, includePath, selectors)
    }

    private fun collectContextUncached(
        workspacePath: String,
        query: String,
        maxFiles: Int,
        maxBytes: Int,
        maxFileBytes: Int,
        includePath: (String) -> Boolean = { true },
        selectors: List<RepositoryEvidenceSelector> = emptyList(),
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
        val affineOwnerNames = tracked.asSequence()
            .map { root.relativize(it).toString().replace('\\', '/') }
            .filter(::isTestSourcePath)
            .map { it.substringAfterLast('/').substringBeforeLast('.').removeSuffix("Test").lowercase() }
            .filter(String::isNotBlank)
            .toSet()
        val selectorMatchers = selectors.associateWith { selector ->
            selector.pathGlobs.map { FileSystems.getDefault().getPathMatcher("glob:$it") }
        }
        val ranked = tracked.mapNotNull { path ->
            val size = runCatching { Files.size(path) }.getOrNull() ?: return@mapNotNull null
            if (size > MAX_CONTEXT_SOURCE_BYTES) return@mapNotNull null
            val bytes = runCatching { Files.readAllBytes(path) }.getOrNull() ?: return@mapNotNull null
            if (bytes.any { it == 0.toByte() }) return@mapNotNull null
            val source = bytes.toString(Charsets.UTF_8)
            val content = focusedContextExcerpt(source, queryTokens, maxFileBytes)
            val relative = root.relativize(path).toString().replace('\\', '/')
            val selectorIds = selectorMatchers.mapNotNull { (selector, matchers) ->
                selector.selectorId.takeIf {
                    matchers.any { it.matches(Path.of(relative)) } && selectorMatchesSource(selector, source)
                }
            }
            val lowerPath = relative.lowercase()
            val sourceTokens = contextTokens(source)
            val score = queryTokens.sumOf { token ->
                (if (lowerPath.contains(token)) 20 else 0) + (if (token in sourceTokens) 1 else 0)
            } + foundationScore(relative) + ownershipScore(relative, queryTokens, selectorIds, affineOwnerNames)
            RankedContextFile(
                score,
                relative,
                CodingContextFile(
                    relative,
                    content,
                    sha256Content(source),
                    matchedSourceDeclarations(source, queryTokens),
                    selectorIds,
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

    private fun isSuspiciousRewrite(original: String, candidate: String): Boolean {
        if (original.length < 1_000) return false
        if (candidate.length * 3 < original.length) return true
        val originalImports = importLineCount(original)
        return originalImports >= 3 && importLineCount(candidate) * 2 < originalImports
    }

    private fun importLineCount(source: String): Int = source.lineSequence()
        .count { it.trimStart().startsWith("import ") }

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
                    if (Files.isRegularFile(target) && !Files.isSymbolicLink(target)) {
                        val original = Files.readString(target, Charsets.UTF_8)
                        val candidate = requireNotNull(operation.content)
                        require(!isSuspiciousRewrite(original, candidate)) {
                            "WRITE ${operation.path} appears truncated; use bounded replacements or provide the complete source"
                        }
                    }
                    require(!Files.exists(target) || !Files.readAllBytes(target).contentEquals(operation.content.toByteArray(Charsets.UTF_8))) {
                        "WRITE ${operation.path} content matches the existing file; every required operation must change its target"
                    }
                }
                CODING_FILE_REPLACE -> {
                    require(operation.content == null && operation.replacements.size in 1..MAX_REPLACEMENTS) {
                        "REPLACE operation requires bounded replacements without complete content"
                    }
                    require(Files.isRegularFile(target) && !Files.isSymbolicLink(target)) {
                        "REPLACE target must be an existing regular file"
                    }
                    val original = Files.readString(target, Charsets.UTF_8)
                    var candidate = original
                    operation.replacements.forEachIndexed { index, replacement ->
                        require(replacement.old.isNotEmpty()) {
                            "REPLACE ${operation.path} replacement ${index + 1} old text is empty"
                        }
                        val occurrenceCount = exactOccurrenceCount(candidate, replacement.old)
                        require(occurrenceCount == 1) {
                            val originalOccurrenceCount = exactOccurrenceCount(original, replacement.old)
                            val anchor = rejectedReplacementAnchor(replacement.old)
                            if (occurrenceCount == 0 && originalOccurrenceCount == 1 && index > 0) {
                                "REPLACE ${operation.path} replacement ${index + 1} old text occurs 0 times after prior replacements " +
                                    "but once in the original source; replacements must use non-overlapping anchors ordered from bottom to top; " +
                                    anchor
                            } else {
                                "REPLACE ${operation.path} replacement ${index + 1} old text occurs $occurrenceCount times; expected exactly once; " +
                                    anchor + replacementAnchorDiagnostic(candidate, replacement.old)
                            }
                        }
                        candidate = candidate.replaceFirst(replacement.old, replacement.new)
                    }
                    require(candidate != original) {
                        val noOpIndices = operation.replacements.mapIndexedNotNull { index, replacement ->
                            (index + 1).takeIf { replacement.old == replacement.new }
                        }
                        if (noOpIndices.isNotEmpty()) {
                            "REPLACE ${operation.path} does not change file content; no-op replacement indices: " +
                                "${noOpIndices.joinToString()}; every replacement new text must differ from its old text"
                        } else {
                            "REPLACE ${operation.path} does not change file content; replacements collectively restore the original source"
                        }
                    }
                    require(original.withoutLineComments() != candidate.withoutLineComments()) {
                        "REPLACE ${operation.path} only changes line comments on unchanged source; " +
                            "cosmetic replacement indices: ${operation.replacements.indices.joinToString { (it + 1).toString() }}; " +
                            "every required operation must change source behavior"
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

    private fun ownershipScore(
        path: String,
        queryTokens: Set<String>,
        selectorIds: List<String>,
        affineOwnerNames: Set<String>,
    ): Int {
        val selectedOwner = selectorIds.isNotEmpty()
        val normalizedPath = path.lowercase()
        val testOwner = isTestSourcePath(path) &&
            queryTokens.any { it !in GENERIC_TEST_TOKENS && normalizedPath.contains(it) }
        val affineProductionOwner = !isTestSourcePath(path) &&
            path.substringAfterLast('/').substringBeforeLast('.').lowercase() in affineOwnerNames
        return (if (selectedOwner) OWNERSHIP_SCORE_BONUS else 0) +
            (if (testOwner) OWNERSHIP_SCORE_BONUS else 0) +
            (if (affineProductionOwner) OWNERSHIP_SCORE_BONUS else 0)
    }

    private fun selectorMatchesSource(selector: RepositoryEvidenceSelector, source: String): Boolean = when {
        selector.contentLiterals.isEmpty() -> true
        selector.contentMatch == REPOSITORY_EVIDENCE_MATCH_ALL -> selector.contentLiterals.all(source::contains)
        else -> selector.contentLiterals.any(source::contains)
    }

    private fun isTestSourcePath(path: String): Boolean {
        val normalized = path.replace('\\', '/').lowercase()
        return "/test/" in normalized || normalized.substringAfterLast('/').contains("test.")
    }

    private fun tokens(value: String): Set<String> = contextTokens(value)

    private fun sha256(value: String): String = sha256Content(value)

    private data class ProcessResult(val exitCode: Int, val output: String)

    private data class RankedContextFile(
        val score: Int,
        val path: String,
        val file: CodingContextFile,
    )

    private companion object {
        const val MAX_REPLACEMENTS = 32
        const val OWNERSHIP_SCORE_BONUS = 10_000
        const val MAX_CONTEXT_FILES = 32
        const val MAX_CONTEXT_FILE_BYTES = 64 * 1024
        const val MAX_CONTEXT_SOURCE_BYTES = 1024 * 1024L
        const val MAX_CONTEXT_BYTES = 256 * 1024
        const val MIN_PLAN_CONTEXT_FILE_BYTES = 128
        const val MAX_ANALYSIS_CONTEXT_FILES = 96
        const val MAX_ANALYSIS_CONTEXT_BYTES = 768 * 1024
        const val MAX_ANALYSIS_CONTEXT_FILE_BYTES = 12 * 1024
        const val MAX_GENESIS_CONTEXT_FILES = 6
        const val MAX_GENESIS_CONTEXT_BYTES = 4 * 1024
        const val MAX_OPERATIONS = 32
        const val MAX_PATCH_BYTES = 2 * 1024 * 1024
        const val MAX_PATH_LENGTH = 512
        const val MAX_SUMMARY_LENGTH = 2_000
        const val MAX_COMMAND_LENGTH = 1_024
        const val MAX_COMMAND_ARGUMENTS = 64
        val GENERIC_TEST_TOKENS = setOf("test", "tests", "regression")
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
        val CONTEXT_JSON = Json { encodeDefaults = true }
    }
}

private fun String.withoutLineComments(): String {
    val source = this
    return buildString(source.length) {
        var index = 0
        var state = KotlinLexicalState.CODE
        var escaped = false
        var blockDepth = 0
        while (index < source.length) {
            when (state) {
                KotlinLexicalState.CODE -> when {
                    source.startsWith("//", index) -> {
                        while (isNotEmpty() && last().isWhitespace() && last() != '\n' && last() != '\r') {
                            deleteCharAt(lastIndex)
                        }
                        index = source.indexOf('\n', index).takeIf { it >= 0 } ?: source.length
                    }
                    source.startsWith("/*", index) -> {
                        append("/*")
                        index += 2
                        blockDepth = 1
                        state = KotlinLexicalState.BLOCK_COMMENT
                    }
                    source.startsWith("\"\"\"", index) -> {
                        append("\"\"\"")
                        index += 3
                        state = KotlinLexicalState.RAW_STRING
                    }
                    source[index] == '"' -> {
                        append('"')
                        index++
                        escaped = false
                        state = KotlinLexicalState.STRING
                    }
                    source[index] == '\'' -> {
                        append('\'')
                        index++
                        escaped = false
                        state = KotlinLexicalState.CHARACTER
                    }
                    else -> append(source[index++])
                }
                KotlinLexicalState.STRING, KotlinLexicalState.CHARACTER -> {
                    val character = source[index++]
                    append(character)
                    when {
                        escaped -> escaped = false
                        character == '\\' -> escaped = true
                        state == KotlinLexicalState.STRING && character == '"' -> state = KotlinLexicalState.CODE
                        state == KotlinLexicalState.CHARACTER && character == '\'' -> state = KotlinLexicalState.CODE
                    }
                }
                KotlinLexicalState.RAW_STRING -> {
                    if (source.startsWith("\"\"\"", index)) {
                        append("\"\"\"")
                        index += 3
                        state = KotlinLexicalState.CODE
                    } else {
                        append(source[index++])
                    }
                }
                KotlinLexicalState.BLOCK_COMMENT -> when {
                    source.startsWith("/*", index) -> {
                        append("/*")
                        index += 2
                        blockDepth++
                    }
                    source.startsWith("*/", index) -> {
                        append("*/")
                        index += 2
                        blockDepth--
                        if (blockDepth == 0) state = KotlinLexicalState.CODE
                    }
                    else -> append(source[index++])
                }
            }
        }
    }
}

private enum class KotlinLexicalState { CODE, STRING, CHARACTER, RAW_STRING, BLOCK_COMMENT }

internal fun focusedContextExcerpt(content: String, queryTokens: Set<String>, maxBytes: Int): String {
    require(maxBytes > 0)
    if (content.encodeToByteArray().size <= maxBytes) return content
    val boundedQueryTokens = queryTokens.asSequence().sorted().take(MAX_LEXICAL_SUMMARY_TOKENS).toSet()
    val lexicalSummary = lexicalMatchSummary(content, boundedQueryTokens, maxBytes / 4)
    val excerptBudget = maxBytes - lexicalSummary.encodeToByteArray().size
    if (excerptBudget <= 0) return lexicalSummary
    val lines = content.split('\n')
    val matches = lines.indices.mapNotNull { index ->
        val lineTokens = contextTokens(lines[index])
        val matchedTokens = boundedQueryTokens.filterTo(mutableSetOf(), lineTokens::contains)
        matchedTokens.size.takeIf { it > 0 }?.let { tokenScore ->
            val declaration = lineTokens.any(SOURCE_DECLARATION_TOKENS::contains)
            ExcerptMatch(
                index,
                tokenScore + if (declaration) DECLARATION_MATCH_BONUS else 0,
                matchedTokens,
                declaration,
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
            if (selectedBytes + sectionBytes <= excerptBudget) {
                selected += window
                selectedBytes += sectionBytes
            }
        }
        selected
    }
    val excerpt = StringBuilder(lexicalSummary)
    windows.sortedBy { it.first }.forEach { window -> excerpt.append(excerptSection(lines, window)) }
    return excerpt.toString().takeIf { it.length > lexicalSummary.length } ?: buildString {
        append(lexicalSummary)
        var bytes = 0
        for (line in lines) {
            val lineBytes = line.encodeToByteArray().size + 1
            if (bytes + lineBytes > excerptBudget) break
            append(line).append('\n')
            bytes += lineBytes
        }
    }
}

internal fun planContextFileBudgets(sourceBytes: List<Int>, totalBytes: Int, minimumBytes: Int): List<Int> {
    require(sourceBytes.isNotEmpty() && minimumBytes > 0 && totalBytes >= minimumBytes * sourceBytes.size)
    val budgets = MutableList(sourceBytes.size) { minimumBytes }
    var remaining = totalBytes - minimumBytes * sourceBytes.size
    val unresolved = sourceBytes.indices.sortedBy(sourceBytes::get).toMutableList()
    while (unresolved.isNotEmpty()) {
        val index = unresolved.first()
        val desired = (sourceBytes[index] - minimumBytes).coerceAtLeast(0)
        if (desired > remaining) break
        budgets[index] += desired
        remaining -= desired
        unresolved.removeAt(0)
    }
    if (remaining > 0 && unresolved.isNotEmpty()) {
        unresolved.forEachIndexed { position, index ->
            val allocation = minOf(
                sourceBytes[index] - budgets[index],
                remaining / (unresolved.size - position),
            )
            budgets[index] += allocation
            remaining -= allocation
        }
    }
    return budgets
}

private fun lexicalMatchSummary(content: String, queryTokens: Set<String>, maxBytes: Int): String {
    if (maxBytes <= 0 || queryTokens.isEmpty()) return ""
    val summaryTokens = queryTokens.asSequence().sorted().take(MAX_LEXICAL_SUMMARY_TOKENS).toSet()
    val counts = summaryTokens.associateWith { 0 }.toMutableMap()
    var tokenStart = -1
    content.forEachIndexed { index, character ->
        if (character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' || character == '_') {
            if (tokenStart < 0) tokenStart = index
        } else if (tokenStart >= 0) {
            val token = content.substring(tokenStart, index).lowercase()
            counts[token]?.let { counts[token] = it + 1 }
            tokenStart = -1
        }
    }
    if (tokenStart >= 0) {
        val token = content.substring(tokenStart).lowercase()
        counts[token]?.let { counts[token] = it + 1 }
    }
    val entries = summaryTokens.asSequence()
        .map { token -> token to counts.getValue(token) }
        .sortedWith(compareBy<Pair<String, Int>> { it.second == 0 }.thenBy { it.first })
        .map { (token, count) -> "$token=$count" }
        .toList()
    val summary = StringBuilder("[Orchard lexical query counts: ")
    for ((index, entry) in entries.withIndex()) {
        val separator = if (index == 0) "" else ", "
        val addition = "$separator$entry"
        if ((summary.length + addition.length + 2) > maxBytes) break
        summary.append(addition)
    }
    if (summary.length == "[Orchard lexical query counts: ".length) return ""
    return summary.append("]\n").toString()
}

internal fun rejectedReplacementAnchor(old: String): String {
    val preview = old.take(MAX_REJECTED_ANCHOR_PREVIEW_CHARS)
    val suffix = if (preview.length == old.length) "" else " (preview truncated)"
    return "rejected old text ${Json.encodeToString(preview)}$suffix, sha256 ${sha256Content(old)}"
}

internal fun ambiguousReplacementAnchorDiagnostic(content: String, old: String): String {
    if (old.isEmpty() || exactOccurrenceCount(content, old) <= 1) return ""
    val lines = content.split('\n')
    val offsets = buildList {
        var offset = 0
        lines.forEach { line ->
            add(offset)
            offset += line.length + 1
        }
    }
    val suggestions = Regex(Regex.escape(old)).findAll(content).mapNotNull { match ->
        val lineIndex = offsets.indexOfLast { it <= match.range.first }
        (0..MAX_AMBIGUOUS_ANCHOR_CONTEXT_LINES).asSequence().mapNotNull { radius ->
            val first = (lineIndex - radius).coerceAtLeast(0)
            val last = (lineIndex + radius).coerceAtMost(lines.lastIndex)
            val suggestion = lines.subList(first, last + 1).joinToString("\n")
            suggestion.takeIf { exactOccurrenceCount(content, it) == 1 && old in it }
        }.firstOrNull()
    }.distinct().take(MAX_AMBIGUOUS_ANCHOR_SUGGESTIONS).toList()
    if (suggestions.isEmpty()) return ""
    return "; submit one separate replacement for each occurrence using these exact source-backed unique anchor suggestions: " +
        suggestions.joinToString(" | ") { Json.encodeToString(it) }
}

internal fun replacementAnchorDiagnostic(content: String, old: String): String =
    if (exactOccurrenceCount(content, old) == 0) {
        "; the old text is absent from pinned source. Do not invent placeholder anchors; copy old text verbatim from the supplied source"
    } else {
        ambiguousReplacementAnchorDiagnostic(content, old)
    }

internal fun matchedSourceDeclarations(content: String, queryTokens: Set<String>): List<String> {
    val matches = content.lineSequence().mapIndexedNotNull { index, line ->
        val lower = line.lowercase()
        val lineTokens = contextTokens(lower)
        if (lineTokens.none(SOURCE_DECLARATION_TOKENS::contains)) return@mapIndexedNotNull null
        val matchedTokens = queryTokens.filterTo(mutableSetOf(), lineTokens::contains)
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
        .map(SourceDeclarationMatch::line)
}

private fun excerptSection(lines: List<String>, window: IntRange): String = buildString {
    append("[Orchard excerpt lines ${window.first + 1}-${window.last + 1} of ${lines.size}]\n")
    window.forEach { index -> append(lines[index]).append('\n') }
}

private const val MAX_EXCERPT_WINDOWS = 64
private const val EXCERPT_CONTEXT_LINES = 3
private const val DECLARATION_MATCH_BONUS = 2
private const val MAX_LEXICAL_SUMMARY_TOKENS = 128
private const val MAX_MATCHED_DECLARATIONS = 64
private const val MAX_DECLARATION_CHARS = 512
private const val MAX_AMBIGUOUS_ANCHOR_CONTEXT_LINES = 3
private const val MAX_AMBIGUOUS_ANCHOR_SUGGESTIONS = 8
private val SOURCE_DECLARATION_TOKENS = setOf("class", "interface", "object", "fun", "val", "var", "typealias")
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

internal fun contextTokens(value: String): Set<String> {
    val tokens = linkedSetOf<String>()
    var start = -1
    fun add(end: Int) {
        if (start >= 0 && end - start >= 3) {
            tokens += value.substring(start, end).lowercase()
        }
        start = -1
    }
    value.forEachIndexed { index, character ->
        if (character in 'A'..'Z' || character in 'a'..'z' || character in '0'..'9' || character == '_') {
            if (start < 0) start = index
        } else {
            add(index)
        }
    }
    add(value.length)
    return tokens
}

private fun exactOccurrenceCount(content: String, value: String): Int {
    var count = 0
    var index = content.indexOf(value)
    while (index >= 0) {
        count += 1
        index = content.indexOf(value, index + 1)
    }
    return count
}
