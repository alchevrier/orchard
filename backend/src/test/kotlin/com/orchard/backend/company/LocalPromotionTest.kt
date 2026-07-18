package com.orchard.backend.company

import com.orchard.backend.workspace.FileRepositoryBindingStore
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalPromotionTest {
    @Test
    fun `local promotion rejects stale authority and fast forwards exact candidate`() {
        val state = createTempDirectory("orchard-promotion-state-")
        val repository = createTempDirectory("orchard-promotion-repository-")
        git(repository, "init")
        Files.writeString(repository.resolve("README.md"), "base\n")
        git(repository, "add", ".")
        git(repository, "-c", "user.name=Test", "-c", "user.email=test@localhost", "commit", "-m", "Base")
        val base = git(repository, "rev-parse", "HEAD")
        val bindings = FileRepositoryBindingStore(state)
        bindings.bind(1, repository.toString())
        val (_, reservation) = bindings.reserveWorkspace(
            1,
            1,
            requireNotNull(bindings.resolveHead(1)),
            integrationOwner = false,
        )
        val worktree = Path.of(reservation.path)
        Files.writeString(worktree.resolve("README.md"), "candidate\n")
        git(worktree, "add", ".")
        git(worktree, "-c", "user.name=Test", "-c", "user.email=test@localhost", "commit", "-m", "Candidate")
        val candidate = git(worktree, "rev-parse", "HEAD")
        val validation = assertNotNull(bindings.validateRevision(1, base, candidate))
        val diffHash = assertNotNull(validation.diffHash)

        assertNull(bindings.promoteLocal(1, base, candidate, "f".repeat(64)))
        assertEquals(base, git(repository, "rev-parse", "HEAD"))

        Files.writeString(repository.resolve("LOCAL.md"), "drift\n")
        git(repository, "add", ".")
        git(repository, "-c", "user.name=Test", "-c", "user.email=test@localhost", "commit", "-m", "Destination drift")
        assertNull(bindings.promoteLocal(1, base, candidate, diffHash))
        git(repository, "reset", "--hard", base)

        val promoted = assertNotNull(bindings.promoteLocal(1, base, candidate, diffHash))
        assertEquals(candidate, promoted.destinationRevision)
        assertEquals(candidate, git(repository, "rev-parse", "HEAD"))
        assertTrue(bindings.resolveHead(1)?.clean == true)
    }

    private fun git(directory: Path, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git", "-C", directory.toString()) + arguments)
            .redirectErrorStream(true)
            .start()
        assertTrue(process.waitFor(10, TimeUnit.SECONDS))
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        assertEquals(0, process.exitValue(), output)
        return output
    }
}
