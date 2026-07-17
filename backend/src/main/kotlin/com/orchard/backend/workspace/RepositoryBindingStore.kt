package com.orchard.backend.workspace

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class RepositoryView(
    val projectId: Int,
    val path: String,
    val available: Boolean,
    val branch: String = "",
    val remote: String = "",
    val dirty: Boolean = false,
    val buildSystem: String = "Unknown",
)

data class RevisionValidation(
    val commitHash: String,
    val changedFromBase: Boolean,
    val diffHash: String? = null,
)

interface RepositoryBindingStore {
    fun bind(projectId: Int, requestedPath: String)
    fun views(projectIds: Set<Int>): Map<Int, RepositoryView>
    fun resolveHead(projectId: Int): RepositoryHead?
    fun validateRevision(projectId: Int, baseRevision: String, targetRevision: String): RevisionValidation? = null
    fun reserveWorkspace(
        projectId: Int,
        dispatchId: Long,
        base: RepositoryHead,
        integrationOwner: Boolean,
    ): Pair<RepositoryHead, DispatchWorkspaceReservation> = base to DispatchWorkspaceReservation(
        mode = if (integrationOwner) "INTEGRATION" else "ISOLATED",
        owner = "circuit-dispatch-$dispatchId",
        path = base.path,
        branch = base.branch,
        baseRevision = base.commitHash,
    )
}

object TransientRepositoryBindingStore : RepositoryBindingStore {
    override fun bind(projectId: Int, requestedPath: String) = Unit
    override fun views(projectIds: Set<Int>): Map<Int, RepositoryView> = emptyMap()
    override fun resolveHead(projectId: Int): RepositoryHead? = null
}

class FileRepositoryBindingStore(private val directory: Path) : RepositoryBindingStore {
    private val bindingPath = directory.resolve("repository-bindings.json")
    private val json = Json { encodeDefaults = true; prettyPrint = true }
    private var bindings = load()

    @Synchronized
    override fun bind(projectId: Int, requestedPath: String) {
        val repository = inspectForBinding(requestedPath)
        val updated = bindings + (projectId to RepositoryBinding(projectId, repository.path))
        write(updated.values.sortedBy { it.projectId })
        bindings = updated
    }

    @Synchronized
    override fun views(projectIds: Set<Int>): Map<Int, RepositoryView> = bindings
        .filterKeys { it in projectIds }
        .mapValues { (_, binding) -> inspectExisting(binding) }

    @Synchronized
    override fun resolveHead(projectId: Int): RepositoryHead? {
        val binding = bindings[projectId] ?: return null
        val root = Path.of(binding.path)
        if (!Files.isDirectory(root)) return null
        val revision = runGit(root, "rev-parse", "--verify", "HEAD")
        if (revision.exitCode != 0 || revision.output.isBlank()) return null
        val status = runGit(root, "status", "--porcelain")
        if (status.exitCode != 0) return null
        val branch = runGit(root, "branch", "--show-current")
        val remote = runGit(root, "config", "--get", "remote.origin.url")
        return RepositoryHead(
            projectId = projectId,
            path = root.toRealPath().toString(),
            commitHash = revision.output,
            branch = branch.output.ifBlank { "detached" },
            remote = remote.output.takeIf { remote.exitCode == 0 }.orEmpty(),
            clean = status.output.isBlank(),
        )
    }

    @Synchronized
    override fun validateRevision(
        projectId: Int,
        baseRevision: String,
        targetRevision: String,
    ): RevisionValidation? {
        if (!baseRevision.matches(GIT_HASH) || !targetRevision.matches(GIT_HASH)) return null
        val binding = bindings[projectId] ?: return null
        val root = Path.of(binding.path)
        if (!Files.isDirectory(root)) return null
        val canonicalTarget = runGit(root, "rev-parse", "--verify", "$targetRevision^{commit}")
        if (canonicalTarget.exitCode != 0 || !canonicalTarget.output.matches(GIT_HASH)) return null
        if (runGit(root, "merge-base", "--is-ancestor", baseRevision, canonicalTarget.output).exitCode != 0) return null
        val diff = runGit(root, "diff", "--quiet", baseRevision, canonicalTarget.output)
        if (diff.exitCode !in 0..1) return null
        val canonicalDiff = runGit(
            root,
            "diff",
            "--binary",
            "--no-ext-diff",
            baseRevision,
            canonicalTarget.output,
        )
        if (canonicalDiff.exitCode != 0) return null
        return RevisionValidation(
            canonicalTarget.output,
            changedFromBase = diff.exitCode == 1,
            diffHash = checksum(
                "${baseRevision.lowercase()}\n${canonicalTarget.output.lowercase()}\n${canonicalDiff.output}"
            ),
        )
    }

    @Synchronized
    override fun reserveWorkspace(
        projectId: Int,
        dispatchId: Long,
        base: RepositoryHead,
        integrationOwner: Boolean,
    ): Pair<RepositoryHead, DispatchWorkspaceReservation> {
        val binding = bindings[projectId] ?: throw IllegalStateException("Project $projectId has no repository binding")
        val root = Path.of(binding.path).toRealPath()
        require(root.toString() == base.path) { "Repository binding changed before dispatch admission" }
        val owner = "circuit-dispatch-$dispatchId"
        val mode = if (integrationOwner) "INTEGRATION" else "ISOLATED"
        val branch = "orchard/$owner"
        val worktree = directory.resolve("worktrees").resolve(owner).toAbsolutePath().normalize()
        Files.createDirectories(worktree.parent)
        if (!Files.exists(worktree.resolve(".git"))) {
            require(!Files.exists(worktree) || Files.list(worktree).use { !it.findAny().isPresent }) {
                "Dispatch worktree path is not empty: $worktree"
            }
            val branchExists = runGit(root, "show-ref", "--verify", "--quiet", "refs/heads/$branch").exitCode == 0
            val result = if (branchExists) {
                runGit(root, "worktree", "add", worktree.toString(), branch)
            } else {
                runGit(root, "worktree", "add", "-b", branch, worktree.toString(), base.commitHash)
            }
            require(result.exitCode == 0) { "Unable to create dispatch worktree: ${result.output}" }
        }
        val revision = runGit(worktree, "rev-parse", "--verify", "HEAD")
        val status = runGit(worktree, "status", "--porcelain")
        require(revision.exitCode == 0 && revision.output == base.commitHash) {
            "Dispatch worktree does not match its pinned base revision"
        }
        require(status.exitCode == 0 && status.output.isBlank()) { "Dispatch worktree is not clean" }
        val repository = base.copy(path = worktree.toString(), branch = branch, clean = true)
        return repository to DispatchWorkspaceReservation(
            mode = mode,
            owner = owner,
            path = worktree.toString(),
            branch = branch,
            baseRevision = base.commitHash,
        )
    }

    private fun load(): Map<Int, RepositoryBinding> {
        Files.createDirectories(directory)
        if (!Files.exists(bindingPath)) return emptyMap()
        val envelope = runCatching {
            json.decodeFromString<RepositoryBindingEnvelope>(Files.readString(bindingPath, Charsets.UTF_8))
        }.getOrElse { throw IllegalStateException("Cannot decode repository bindings at $bindingPath", it) }
        val stateJson = compactJson.encodeToString(envelope.state)
        check(envelope.checksum == checksum(stateJson)) { "Repository binding checksum mismatch at $bindingPath" }
        check(envelope.state.version == FORMAT_VERSION) {
            "Unsupported repository binding version ${envelope.state.version} at $bindingPath"
        }
        require(envelope.state.bindings.map { it.projectId }.distinct().size == envelope.state.bindings.size) {
            "Repository bindings contain duplicate project IDs"
        }
        return envelope.state.bindings.associateBy { it.projectId }
    }

    private fun write(updated: List<RepositoryBinding>) {
        val state = RepositoryBindingState(bindings = updated)
        val envelope = RepositoryBindingEnvelope(state, checksum(compactJson.encodeToString(state)))
        val temporaryPath = bindingPath.resolveSibling("${bindingPath.fileName}.tmp")
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
        try {
            Files.move(temporaryPath, bindingPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporaryPath, bindingPath, StandardCopyOption.REPLACE_EXISTING)
        }
        runCatching { FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) } }
    }

    private fun inspectForBinding(requestedPath: String): RepositoryView {
        val candidate = runCatching { Path.of(requestedPath) }
            .getOrElse { throw IllegalArgumentException("Repository path is invalid", it) }
        require(candidate.isAbsolute) { "Repository path must be absolute" }
        require(Files.isDirectory(candidate)) { "Repository path must be an existing directory" }
        val realPath = candidate.toRealPath()
        val root = runGit(realPath, "rev-parse", "--show-toplevel")
        require(root.exitCode == 0 && root.output.isNotBlank()) { "Directory is not inside a Git repository" }
        return inspect(Path.of(root.output).toRealPath(), projectId = 0)
    }

    private fun inspectExisting(binding: RepositoryBinding): RepositoryView {
        val path = Path.of(binding.path)
        if (!Files.isDirectory(path)) return RepositoryView(binding.projectId, binding.path, available = false)
        return runCatching { inspect(path.toRealPath(), binding.projectId) }
            .getOrElse { RepositoryView(binding.projectId, binding.path, available = false) }
    }

    private fun inspect(root: Path, projectId: Int): RepositoryView {
        val branchResult = runGit(root, "branch", "--show-current")
        require(branchResult.exitCode == 0) { "Unable to inspect Git branch" }
        val statusResult = runGit(root, "status", "--porcelain")
        require(statusResult.exitCode == 0) { "Unable to inspect Git status" }
        val remoteResult = runGit(root, "config", "--get", "remote.origin.url")
        return RepositoryView(
            projectId = projectId,
            path = root.toString(),
            available = true,
            branch = branchResult.output.ifBlank { "detached" },
            remote = remoteResult.output.takeIf { remoteResult.exitCode == 0 }.orEmpty(),
            dirty = statusResult.output.isNotBlank(),
            buildSystem = detectBuildSystem(root),
        )
    }

    private fun runGit(directory: Path, vararg arguments: String): CommandResult {
        val outputPath = Files.createTempFile("orchard-git-inspection-", ".log")
        try {
            val processBuilder = ProcessBuilder(listOf("git", "-C", directory.toString()) + arguments)
                .redirectErrorStream(true)
                .redirectOutput(outputPath.toFile())
            processBuilder.environment()["GIT_OPTIONAL_LOCKS"] = "0"
            val process = processBuilder.start()
            val completed = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                throw IllegalStateException("Git inspection timed out")
            }
            return CommandResult(process.exitValue(), Files.readString(outputPath, Charsets.UTF_8).trim())
        } finally {
            Files.deleteIfExists(outputPath)
        }
    }

    private fun detectBuildSystem(root: Path): String = when {
        Files.exists(root.resolve("settings.gradle.kts")) || Files.exists(root.resolve("settings.gradle")) -> "Gradle"
        Files.exists(root.resolve("pom.xml")) -> "Maven"
        Files.exists(root.resolve("meson.build")) -> "Meson"
        Files.exists(root.resolve("CMakeLists.txt")) -> "CMake"
        Files.exists(root.resolve("Cargo.toml")) -> "Cargo"
        Files.exists(root.resolve("package.json")) -> "Node"
        else -> "Unknown"
    }

    private fun checksum(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

    private data class CommandResult(val exitCode: Int, val output: String)

    private companion object {
        const val FORMAT_VERSION = 1
        const val COMMAND_TIMEOUT_SECONDS = 5L
        val compactJson = Json { encodeDefaults = true }
        val GIT_HASH = Regex("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}")
    }
}

@Serializable
private data class RepositoryBinding(val projectId: Int, val path: String)

@Serializable
private data class RepositoryBindingState(
    val version: Int = 1,
    val bindings: List<RepositoryBinding>,
)

@Serializable
private data class RepositoryBindingEnvelope(
    val state: RepositoryBindingState,
    val checksum: String,
)