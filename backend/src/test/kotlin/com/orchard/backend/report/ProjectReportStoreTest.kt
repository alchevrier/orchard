package com.orchard.backend.report

import java.nio.file.Files
import java.nio.file.StandardOpenOption
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProjectReportStoreTest {
    @Test
    fun `file ledger restores idempotent publications and immutable successors`() {
        val directory = createTempDirectory("orchard-project-reports-")
        val store = FileProjectReportStore(directory)
        val pending = publish(store, "pending", "a".repeat(64))

        assertEquals(pending, publish(store, "pending", "a".repeat(64)))
        val active = store.appendSubscription(pending.report.reportId, REPORT_MODE_ALL, REPORT_SUBSCRIPTION_ACTIVE, "HUMAN")
        val paused = store.appendSubscription(pending.report.reportId, REPORT_MODE_ALL, REPORT_SUBSCRIPTION_PAUSED, "HUMAN")
        assertEquals(active.revision, paused.previousRevision)
        assertEquals(pending.report.reportId, FileProjectReportStore(directory).events().first().report!!.reportId)
        assertEquals(3, Files.readAllLines(directory.resolve("project-reports.jsonl")).size)
        assertFailsWith<IllegalArgumentException> { publish(store, "pending", "b".repeat(64)) }
    }

    @Test
    fun `reads and canonical threads are idempotent`() {
        val store = TransientProjectReportStore()
        val publication = publish(store, "pending", "a".repeat(64))

        val read = store.markRead(publication.report.reportId, publication.revision.revision, "HUMAN")
        assertEquals(read, store.markRead(publication.report.reportId, publication.revision.revision, "HUMAN"))
        val thread = store.linkThread(1, REPORT_TARGET_REPORT, publication.report.reportId, 9)
        assertEquals(thread, store.linkThread(1, REPORT_TARGET_REPORT, publication.report.reportId, 10))
        assertEquals(3, store.events().size)
        assertFailsWith<IllegalArgumentException> { store.markRead(publication.report.reportId, 99, "HUMAN") }
        assertFailsWith<IllegalArgumentException> {
            store.appendSubscription(publication.report.reportId, "EVERYTHING", REPORT_SUBSCRIPTION_ACTIVE, "HUMAN")
        }
    }

    @Test
    fun `file ledger quarantines an incomplete tail and rejects checksum corruption`() {
        val directory = createTempDirectory("orchard-project-report-recovery-")
        val store = FileProjectReportStore(directory)
        publish(store, "pending", "a".repeat(64))
        val path = directory.resolve("project-reports.jsonl")
        Files.writeString(path, "{\"incomplete\":", StandardOpenOption.APPEND)

        assertEquals(1, FileProjectReportStore(directory).events().size)
        assertEquals(1, Files.list(directory).use { paths ->
            paths.filter { it.fileName.toString().startsWith("project-reports.corrupt-") }.count()
        })
        publish(store, "assessed", "b".repeat(64))
        Files.writeString(path, Files.readString(path).replaceFirst("\"checksum\":\"", "\"checksum\":\"0"))
        assertFailsWith<IllegalStateException> { FileProjectReportStore(directory).events() }
    }

    private fun publish(store: ProjectReportStore, sourceRevision: String, sourceHash: String) = store.publish(
        projectId = 1,
        reportKey = "repository-baseline",
        scope = ReportScope(REPORT_SCOPE_PROJECT, "1"),
        title = "Repository baseline",
        sourceType = "REPOSITORY_BASELINE",
        sourceIdentity = "project:1",
        sourceRevision = sourceRevision,
        sourceHash = sourceHash,
        state = "PENDING",
        items = listOf(ReportItemInput("baseline", "STATUS", "PENDING", "Analysis pending", "Repository analysis is pending.")),
    )
}