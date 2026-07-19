package com.orchard.backend.workspace

import com.orchard.backend.api.DocumentIntent
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Comparator
import java.util.concurrent.TimeUnit

const val REPOSITORY_SOURCE_LOCAL_FOLDER = "LOCAL_FOLDER"
const val REPOSITORY_SOURCE_GIT_URL = "GIT_URL"

data class RepositoryOnboardingRequest(
    val source: String,
    val location: String,
    val projectTitle: String,
    val projectId: Int = 0,
)

enum class RepositoryOnboardingStatus {
    ONBOARDED,
    INVALID_SOURCE,
    CLONE_FAILED,
    PROJECT_NOT_FOUND,
    PROJECT_CREATION_FAILED,
    REPOSITORY_BIND_FAILED,
}

data class RepositoryOnboardingResult(
    val status: RepositoryOnboardingStatus,
    val project: WorkspaceEntity? = null,
    val repository: RepositoryView? = null,
    val diagnostic: String = "",
)

class RepositoryOnboardingService(
    private val workspace: WorkspaceStore,
    private val managedRepositoriesDirectory: Path,
) {
    @Synchronized
    fun findExisting(request: RepositoryOnboardingRequest): RepositoryOnboardingResult? {
        val repositories = workspace.snapshot(MESSAGE_READY).repositories.values
        val repository = when (request.source) {
            REPOSITORY_SOURCE_LOCAL_FOLDER -> {
                val source = inspectLocalRepository(request.location) ?: return null
                repositories.singleOrNull { repository ->
                    runCatching { Path.of(repository.path).toRealPath() }.getOrNull() == source
                }
            }
            REPOSITORY_SOURCE_GIT_URL -> {
                val source = runCatching { canonicalRemote(request.location).toString() }.getOrNull() ?: return null
                repositories.singleOrNull { repository ->
                    runCatching { canonicalRemote(repository.remote).toString() }.getOrNull() == source
                }
            }
            else -> null
        } ?: return null
        val project = workspace.entities().singleOrNull { it.id == repository.projectId && it.type == ENTITY_PROJECT }
            ?: return null
        return RepositoryOnboardingResult(RepositoryOnboardingStatus.ONBOARDED, project, repository)
    }

    @Synchronized
    fun onboard(
        request: RepositoryOnboardingRequest,
        conversationCommand: ConversationCommandReference,
    ): RepositoryOnboardingResult {
        val selectedProject = request.projectId.takeIf { it > 0 }?.let { projectId ->
            workspace.entities().singleOrNull { it.id == projectId && it.type == ENTITY_PROJECT }
                ?: return RepositoryOnboardingResult(
                    RepositoryOnboardingStatus.PROJECT_NOT_FOUND,
                    diagnostic = "Project $projectId does not exist.",
                )
        }
        val repositoryPath = when (request.source) {
            REPOSITORY_SOURCE_LOCAL_FOLDER -> inspectLocalRepository(request.location)
                ?: return RepositoryOnboardingResult(
                    RepositoryOnboardingStatus.INVALID_SOURCE,
                    diagnostic = "The local folder is not an absolute path inside an existing Git repository.",
                )
            REPOSITORY_SOURCE_GIT_URL -> try {
                materializeRemote(request.location)
            } catch (error: IllegalArgumentException) {
                return RepositoryOnboardingResult(
                    RepositoryOnboardingStatus.INVALID_SOURCE,
                    diagnostic = error.message.orEmpty(),
                )
            } catch (error: Exception) {
                return RepositoryOnboardingResult(
                    RepositoryOnboardingStatus.CLONE_FAILED,
                    diagnostic = error.message ?: "The remote repository could not be cloned.",
                )
            }
            else -> return RepositoryOnboardingResult(
                RepositoryOnboardingStatus.INVALID_SOURCE,
                diagnostic = "Repository source must be LOCAL_FOLDER or GIT_URL.",
            )
        }

        val project = when {
            selectedProject != null -> selectedProject
            else -> workspace.entities().singleOrNull { it.conversationCommand == conversationCommand }
                ?: createProject(request.projectTitle, conversationCommand)
                ?: return RepositoryOnboardingResult(
                    RepositoryOnboardingStatus.PROJECT_CREATION_FAILED,
                    diagnostic = "The project could not be created.",
                )
        }

        val bindResult = workspace.bindRepository(project.id, repositoryPath.toString())
        if (bindResult.status != RepositoryBindStatus.BOUND) {
            return RepositoryOnboardingResult(
                RepositoryOnboardingStatus.REPOSITORY_BIND_FAILED,
                project,
                diagnostic = "Repository binding failed with ${bindResult.status}.",
            )
        }
        val repository = bindResult.snapshot.repositories[project.id]
            ?: return RepositoryOnboardingResult(
                RepositoryOnboardingStatus.REPOSITORY_BIND_FAILED,
                project,
                diagnostic = "The bound repository could not be projected.",
            )
        return RepositoryOnboardingResult(RepositoryOnboardingStatus.ONBOARDED, project, repository)
    }

    private fun createProject(
        title: String,
        conversationCommand: ConversationCommandReference,
    ): WorkspaceEntity? {
        if (title.isBlank()) return null
        synchronized(workspace) {
            workspace.beginBatch()
            try {
                val accepted = workspace.applyIntent(DocumentIntent(
                    ACTION_CREATE,
                    ENTITY_PROJECT,
                    DEFAULT_DELIVERY_WORKFLOW_ID,
                    title = title.trim(),
                    content = "",
                    conversationCommand = conversationCommand,
                ))
                if (!accepted) {
                    workspace.rollbackBatch()
                    return null
                }
                val projectId = workspace.lastCreatedId
                workspace.commitBatch()
                return workspace.entities().single { it.id == projectId }
            } catch (error: Exception) {
                runCatching { workspace.rollbackBatch() }
                throw error
            }
        }
    }

    private fun inspectLocalRepository(location: String): Path? {
        val candidate = runCatching { Path.of(location) }.getOrNull() ?: return null
        if (!candidate.isAbsolute || !Files.isDirectory(candidate)) return null
        val root = runGit(candidate.toRealPath(), listOf("rev-parse", "--show-toplevel"), INSPECTION_TIMEOUT_SECONDS)
        if (root.exitCode != 0 || root.output.isBlank()) return null
        return runCatching { Path.of(root.output).toRealPath() }.getOrNull()
    }

    private fun materializeRemote(location: String): Path {
        val remote = canonicalRemote(location)
        Files.createDirectories(managedRepositoriesDirectory)
        val slug = remote.path.substringAfterLast('/').removeSuffix(".git")
            .lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-').ifBlank { "repository" }
        val destination = managedRepositoriesDirectory.resolve("$slug-${checksum(remote.toString()).take(12)}")
            .toAbsolutePath().normalize()
        require(destination.parent == managedRepositoriesDirectory.toAbsolutePath().normalize()) {
            "Managed repository destination escaped Orchard storage."
        }
        if (Files.exists(destination)) {
            require(existingRemote(destination) == remote.toString()) {
                "Managed repository destination exists with a different origin."
            }
            return destination.toRealPath()
        }

        val temporary = Files.createTempDirectory(managedRepositoriesDirectory, ".$slug-clone-")
        try {
            val clone = runProcess(
                managedRepositoriesDirectory,
                listOf(
                    "git", "-c", "filter.lfs.smudge=", "-c", "filter.lfs.required=false",
                    "clone", "--no-recurse-submodules", "--", remote.toString(), temporary.toString(),
                ),
                CLONE_TIMEOUT_SECONDS,
            )
            check(clone.exitCode == 0) { "Git clone failed: ${clone.output.take(1_000)}" }
            check(existingRemote(temporary) == remote.toString()) { "Cloned repository origin does not match the admitted URL." }
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, destination)
            }
            return destination.toRealPath()
        } finally {
            if (Files.exists(temporary)) deleteManagedTemporary(temporary)
        }
    }

    private fun canonicalRemote(location: String): URI {
        val uri = runCatching { URI(location.trim()).normalize() }.getOrNull()
            ?: throw IllegalArgumentException("The Git URL is invalid.")
        require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()) {
            "Only HTTP(S) Git URLs are supported during onboarding."
        }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "Git URLs cannot contain credentials, queries, or fragments."
        }
        require(uri.path.isNotBlank() && uri.path != "/") { "The Git URL does not identify a repository." }
        return URI(uri.scheme.lowercase(), null, uri.host.lowercase(), uri.port, uri.path.removeSuffix("/"), null, null)
    }

    private fun existingRemote(repository: Path): String? {
        val result = runGit(repository, listOf("config", "--get", "remote.origin.url"), INSPECTION_TIMEOUT_SECONDS)
        if (result.exitCode != 0) return null
        return runCatching { canonicalRemote(result.output).toString() }.getOrNull()
    }

    private fun runGit(directory: Path, arguments: List<String>, timeoutSeconds: Long): CommandResult =
        runProcess(directory, listOf("git", "-C", directory.toString()) + arguments, timeoutSeconds)

    private fun runProcess(directory: Path, command: List<String>, timeoutSeconds: Long): CommandResult {
        val output = Files.createTempFile("orchard-repository-onboarding-", ".log")
        try {
            val process = ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .apply {
                    environment()["GIT_TERMINAL_PROMPT"] = "0"
                    environment()["GIT_LFS_SKIP_SMUDGE"] = "1"
                    environment()["GIT_OPTIONAL_LOCKS"] = "0"
                    environment()["GIT_CONFIG_NOSYSTEM"] = "1"
                    environment()["GIT_CONFIG_GLOBAL"] = "/dev/null"
                }
                .start()
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw IllegalStateException("Git operation timed out.")
            }
            return CommandResult(process.exitValue(), Files.readString(output).trim())
        } finally {
            Files.deleteIfExists(output)
        }
    }

    private fun deleteManagedTemporary(path: Path) {
        Files.walk(path).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private fun checksum(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }

    private data class CommandResult(val exitCode: Int, val output: String)

    private companion object {
        const val INSPECTION_TIMEOUT_SECONDS = 10L
        const val CLONE_TIMEOUT_SECONDS = 180L
    }
}