package com.orchard.backend.workspace

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RepositoryOnboardingServiceTest {
    @Test
    fun `local folder onboarding creates one command correlated project and survives restart`() {
        val root = createTempDirectory("orchard-local-onboarding-")
        val source = root.resolve("source")
        val nested = source.resolve("src/main")
        Files.createDirectories(nested)
        Files.writeString(source.resolve("README.md"), "# Local\n")
        initializeRepository(source)
        val state = root.resolve("state")
        val command = ConversationCommandReference(7, "a".repeat(64), "ONBOARD_REPOSITORY")

        val firstWorkspace = durableWorkspace(state)
        val first = RepositoryOnboardingService(firstWorkspace, root.resolve("managed")).onboard(
            RepositoryOnboardingRequest(REPOSITORY_SOURCE_LOCAL_FOLDER, nested.toString(), "Local project"),
            command,
        )
        val duplicate = RepositoryOnboardingService(firstWorkspace, root.resolve("managed")).onboard(
            RepositoryOnboardingRequest(REPOSITORY_SOURCE_LOCAL_FOLDER, nested.toString(), "Local project"),
            command,
        )

        assertEquals(RepositoryOnboardingStatus.ONBOARDED, first.status)
        assertEquals(RepositoryOnboardingStatus.ONBOARDED, duplicate.status)
        assertEquals(1, firstWorkspace.entityCount)
        assertEquals(command, first.project?.conversationCommand)
        assertEquals(source.toRealPath().toString(), first.repository?.path)

        val recoveredWorkspace = durableWorkspace(state)
        val recovered = RepositoryOnboardingService(recoveredWorkspace, root.resolve("managed")).onboard(
            RepositoryOnboardingRequest(REPOSITORY_SOURCE_LOCAL_FOLDER, source.toString(), "Ignored on recovery"),
            command,
        )
        assertEquals(RepositoryOnboardingStatus.ONBOARDED, recovered.status)
        assertEquals(1, recoveredWorkspace.entityCount)
        assertEquals(first.project?.id, recovered.project?.id)
    }

    @Test
    fun `HTTP Git URL is cloned into managed storage and reused exactly`() {
        val root = createTempDirectory("orchard-url-onboarding-")
        val source = root.resolve("source")
        Files.createDirectories(source)
        Files.writeString(source.resolve("README.md"), "# Remote\n")
        initializeRepository(source)
        val served = root.resolve("served")
        Files.createDirectories(served)
        run(root, "git", "clone", "--bare", source.toString(), served.resolve("sample.git").toString())
        run(served.resolve("sample.git"), "git", "update-server-info")
        val server = serve(served)
        try {
            val url = "http://127.0.0.1:${server.address.port}/sample.git"
            val workspace = durableWorkspace(root.resolve("state"))
            val managed = root.resolve("managed")
            val service = RepositoryOnboardingService(workspace, managed)
            val command = ConversationCommandReference(8, "b".repeat(64), "ONBOARD_REPOSITORY")

            val first = service.onboard(
                RepositoryOnboardingRequest(REPOSITORY_SOURCE_GIT_URL, url, "Remote project"),
                command,
            )
            val duplicate = service.onboard(
                RepositoryOnboardingRequest(REPOSITORY_SOURCE_GIT_URL, url, "Remote project"),
                command,
            )

            assertEquals(RepositoryOnboardingStatus.ONBOARDED, first.status, first.diagnostic)
            assertEquals(RepositoryOnboardingStatus.ONBOARDED, duplicate.status, duplicate.diagnostic)
            assertEquals(1, workspace.entityCount)
            val repository = assertNotNull(first.repository)
            assertTrue(Path.of(repository.path).startsWith(managed.toRealPath()))
            assertEquals(url, repository.remote)
            Files.list(managed).use { entries -> assertEquals(1, entries.count()) }

            val existing = assertNotNull(service.findExisting(
                RepositoryOnboardingRequest(REPOSITORY_SOURCE_GIT_URL, "$url/", "Ignored duplicate title")
            ))
            assertEquals(first.project?.id, existing.project?.id)
            assertEquals(repository.path, existing.repository?.path)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `URL onboarding rejects embedded credentials before creating authority`() {
        val root = createTempDirectory("orchard-url-credentials-")
        val workspace = durableWorkspace(root.resolve("state"))
        val result = RepositoryOnboardingService(workspace, root.resolve("managed")).onboard(
            RepositoryOnboardingRequest(REPOSITORY_SOURCE_GIT_URL, "https://secret@example.test/repository.git", "Rejected"),
            ConversationCommandReference(9, "c".repeat(64), "ONBOARD_REPOSITORY"),
        )

        assertEquals(RepositoryOnboardingStatus.INVALID_SOURCE, result.status)
        assertEquals(0, workspace.entityCount)
    }

    @Test
    fun `URL onboarding rejects missing explicit project before materialization`() {
        val root = createTempDirectory("orchard-url-project-")
        val managed = root.resolve("managed")
        val result = RepositoryOnboardingService(durableWorkspace(root.resolve("state")), managed).onboard(
            RepositoryOnboardingRequest(
                REPOSITORY_SOURCE_GIT_URL,
                "https://example.test/repository.git",
                "Missing project",
                projectId = 42,
            ),
            ConversationCommandReference(10, "d".repeat(64), "ONBOARD_REPOSITORY"),
        )

        assertEquals(RepositoryOnboardingStatus.PROJECT_NOT_FOUND, result.status)
        assertTrue(!Files.exists(managed))
    }

    private fun durableWorkspace(state: Path) = WorkspaceStore(
        FileWorkspaceRepository(state),
        FileRepositoryBindingStore(state),
    )

    private fun initializeRepository(directory: Path) {
        run(directory, "git", "init", "--initial-branch=main")
        run(directory, "git", "config", "user.email", "orchard-test@localhost")
        run(directory, "git", "config", "user.name", "Orchard Test")
        run(directory, "git", "add", ".")
        run(directory, "git", "commit", "-m", "Initial")
    }

    private fun serve(root: Path): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val requested = root.resolve(exchange.requestURI.path.removePrefix("/")).normalize()
            if (!requested.startsWith(root) || !Files.isRegularFile(requested)) {
                exchange.sendResponseHeaders(404, -1)
            } else {
                val content = Files.readAllBytes(requested)
                exchange.responseHeaders.add("Content-Type", "application/octet-stream")
                exchange.sendResponseHeaders(200, content.size.toLong())
                exchange.responseBody.use { it.write(content) }
            }
            exchange.close()
        }
        server.start()
        return server
    }

    private fun run(directory: Path, vararg command: String) {
        val process = ProcessBuilder(command.toList()).directory(directory.toFile()).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), output)
    }
}