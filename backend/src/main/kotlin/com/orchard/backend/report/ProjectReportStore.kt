package com.orchard.backend.report

import com.orchard.backend.workspace.loadRecoverableJsonl
import com.orchard.backend.workspace.stagedPlanHash
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val REPORT_SCOPE_PROJECT = "PROJECT"
const val REPORT_SCOPE_TICKET = "TICKET"
const val REPORT_SCOPE_OUTCOME = "OUTCOME"
const val REPORT_SCOPE_CAPABILITY = "CAPABILITY"
const val REPORT_SCOPE_REPOSITORY_AREA = "REPOSITORY_AREA"

const val REPORT_MODE_IMPORTANT = "IMPORTANT"
const val REPORT_MODE_MILESTONES = "MILESTONES"
const val REPORT_MODE_ACTION_REQUIRED = "ACTION_REQUIRED"
const val REPORT_MODE_ALL = "ALL"

const val REPORT_SUBSCRIPTION_ACTIVE = "ACTIVE"
const val REPORT_SUBSCRIPTION_PAUSED = "PAUSED"
const val REPORT_SUBSCRIPTION_CLOSED = "CLOSED"

const val REPORT_TARGET_REPORT = "REPORT"
const val REPORT_TARGET_TICKET = "TICKET"

@Serializable
data class ReportScope(val type: String, val targetId: String)

@Serializable
data class ProjectReport(
    val reportId: Long,
    val projectId: Int,
    val key: String,
    val scope: ReportScope,
    val title: String,
    val createdAt: String,
)

@Serializable
data class ReportRevision(
    val reportId: Long,
    val revision: Int,
    val sourceType: String,
    val sourceIdentity: String,
    val sourceRevision: String,
    val sourceHash: String,
    val repositoryRevision: String = "",
    val genesisRevision: Int? = null,
    val state: String,
    val createdAt: String,
    val contentHash: String,
)

@Serializable
data class ReportEvidenceReference(
    val type: String,
    val identity: String,
    val revision: String = "",
    val hash: String = "",
    val description: String = "",
)

@Serializable
data class ReportItem(
    val reportId: Long,
    val reportRevision: Int,
    val itemKey: String,
    val kind: String,
    val state: String,
    val title: String,
    val summary: String,
    val actionRequired: Boolean = false,
    val evidence: List<ReportEvidenceReference> = emptyList(),
)

@Serializable
data class ReportSubscription(
    val subscriptionId: Long,
    val reportId: Long,
    val revision: Int,
    val previousRevision: Int? = null,
    val mode: String,
    val state: String,
    val actor: String,
    val createdAt: String,
    val hash: String,
)

@Serializable
data class ReportRead(
    val reportId: Long,
    val reportRevision: Int,
    val actor: String,
    val readAt: String,
)

@Serializable
data class ReportThreadLink(
    val threadLinkId: Long,
    val projectId: Int,
    val targetType: String,
    val targetId: Long,
    val conversationId: Long,
    val createdAt: String,
)

@Serializable
data class ReportItemInput(
    val itemKey: String,
    val kind: String,
    val state: String,
    val title: String,
    val summary: String,
    val actionRequired: Boolean = false,
    val evidence: List<ReportEvidenceReference> = emptyList(),
)

@Serializable
data class ReportPublication(
    val report: ProjectReport,
    val revision: ReportRevision,
    val items: List<ReportItem>,
)

interface ProjectReportStore {
    fun events(): List<ProjectReportEvent>
    fun publish(
        projectId: Int,
        reportKey: String,
        scope: ReportScope,
        title: String,
        sourceType: String,
        sourceIdentity: String,
        sourceRevision: String,
        sourceHash: String,
        repositoryRevision: String = "",
        genesisRevision: Int? = null,
        state: String,
        items: List<ReportItemInput>,
        createdAt: String = Instant.now().toString(),
    ): ReportPublication
    fun appendSubscription(
        reportId: Long,
        mode: String,
        state: String,
        actor: String,
        createdAt: String = Instant.now().toString(),
    ): ReportSubscription
    fun markRead(
        reportId: Long,
        reportRevision: Int,
        actor: String,
        readAt: String = Instant.now().toString(),
    ): ReportRead
    fun linkThread(
        projectId: Int,
        targetType: String,
        targetId: Long,
        conversationId: Long,
        createdAt: String = Instant.now().toString(),
    ): ReportThreadLink
}

class TransientProjectReportStore : ProjectReportStore {
    private val records = mutableListOf<ProjectReportEvent>()

    @Synchronized
    override fun events(): List<ProjectReportEvent> = records.toList()

    @Synchronized
    override fun publish(
        projectId: Int,
        reportKey: String,
        scope: ReportScope,
        title: String,
        sourceType: String,
        sourceIdentity: String,
        sourceRevision: String,
        sourceHash: String,
        repositoryRevision: String,
        genesisRevision: Int?,
        state: String,
        items: List<ReportItemInput>,
        createdAt: String,
    ): ReportPublication = publishInto(
        records, projectId, reportKey, scope, title, sourceType, sourceIdentity,
        sourceRevision, sourceHash, repositoryRevision, genesisRevision, state, items, createdAt, ::append,
    )

    @Synchronized
    override fun appendSubscription(
        reportId: Long,
        mode: String,
        state: String,
        actor: String,
        createdAt: String,
    ): ReportSubscription = subscriptionInto(records, reportId, mode, state, actor, createdAt, ::append)

    @Synchronized
    override fun markRead(reportId: Long, reportRevision: Int, actor: String, readAt: String): ReportRead =
        readInto(records, reportId, reportRevision, actor, readAt, ::append)

    @Synchronized
    override fun linkThread(
        projectId: Int,
        targetType: String,
        targetId: Long,
        conversationId: Long,
        createdAt: String,
    ): ReportThreadLink = threadInto(records, projectId, targetType, targetId, conversationId, createdAt, ::append)

    private fun append(event: ProjectReportEvent) {
        validateReportEvent(event, records)
        records += event
    }
}

class FileProjectReportStore(private val directory: Path) : ProjectReportStore {
    private val path = directory.resolve("project-reports.jsonl")
    private val lockPath = directory.resolve("project-reports.lock")
    private val json = Json { encodeDefaults = true }

    @Synchronized
    override fun events(): List<ProjectReportEvent> = locked { loadUnlocked() }

    @Synchronized
    override fun publish(
        projectId: Int,
        reportKey: String,
        scope: ReportScope,
        title: String,
        sourceType: String,
        sourceIdentity: String,
        sourceRevision: String,
        sourceHash: String,
        repositoryRevision: String,
        genesisRevision: Int?,
        state: String,
        items: List<ReportItemInput>,
        createdAt: String,
    ): ReportPublication = locked {
        val records = loadUnlocked().toMutableList()
        publishInto(
            records, projectId, reportKey, scope, title, sourceType, sourceIdentity,
            sourceRevision, sourceHash, repositoryRevision, genesisRevision, state, items, createdAt,
        ) { appendUnlocked(it, records) }
    }

    @Synchronized
    override fun appendSubscription(
        reportId: Long,
        mode: String,
        state: String,
        actor: String,
        createdAt: String,
    ): ReportSubscription = locked {
        val records = loadUnlocked().toMutableList()
        subscriptionInto(records, reportId, mode, state, actor, createdAt) { appendUnlocked(it, records) }
    }

    @Synchronized
    override fun markRead(reportId: Long, reportRevision: Int, actor: String, readAt: String): ReportRead = locked {
        val records = loadUnlocked().toMutableList()
        readInto(records, reportId, reportRevision, actor, readAt) { appendUnlocked(it, records) }
    }

    @Synchronized
    override fun linkThread(
        projectId: Int,
        targetType: String,
        targetId: Long,
        conversationId: Long,
        createdAt: String,
    ): ReportThreadLink = locked {
        val records = loadUnlocked().toMutableList()
        threadInto(records, projectId, targetType, targetId, conversationId, createdAt) { appendUnlocked(it, records) }
    }

    private fun loadUnlocked(): List<ProjectReportEvent> = mutableListOf<ProjectReportEvent>().also { records ->
        loadRecoverableJsonl(path, "project-reports") { line, recordNumber ->
            val envelope = json.decodeFromString<ProjectReportEnvelope>(line)
            require(envelope.version == FORMAT_VERSION) { "Unsupported project report format ${envelope.version}" }
            require(envelope.checksum == stagedPlanHash(json.encodeToString(envelope.value))) {
                "Checksum mismatch in project report event $recordNumber"
            }
            validateReportEvent(envelope.value, records)
            records += envelope.value
            envelope.value
        }
    }

    private fun appendUnlocked(event: ProjectReportEvent, records: MutableList<ProjectReportEvent>) {
        validateReportEvent(event, records)
        val payload = json.encodeToString(event)
        val line = json.encodeToString(ProjectReportEnvelope(value = event, checksum = stagedPlanHash(payload))) + "\n"
        FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { channel ->
            val bytes = ByteBuffer.wrap(line.toByteArray(Charsets.UTF_8))
            while (bytes.hasRemaining()) channel.write(bytes)
            channel.force(true)
        }
        FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
        records += event
    }

    private fun <T> locked(block: () -> T): T {
        Files.createDirectories(directory)
        return FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use { block() }
        }
    }

    private companion object {
        const val FORMAT_VERSION = 1
    }
}

@Serializable
data class ProjectReportEvent(
    val eventId: Long,
    val type: String,
    val report: ProjectReport? = null,
    val revision: ReportRevision? = null,
    val items: List<ReportItem> = emptyList(),
    val subscription: ReportSubscription? = null,
    val read: ReportRead? = null,
    val threadLink: ReportThreadLink? = null,
)

private fun publishInto(
    records: MutableList<ProjectReportEvent>,
    projectId: Int,
    reportKey: String,
    scope: ReportScope,
    title: String,
    sourceType: String,
    sourceIdentity: String,
    sourceRevision: String,
    sourceHash: String,
    repositoryRevision: String,
    genesisRevision: Int?,
    state: String,
    itemInputs: List<ReportItemInput>,
    createdAt: String,
    append: (ProjectReportEvent) -> Unit,
): ReportPublication {
    val reports = records.mapNotNull { it.report }
    val report = reports.singleOrNull { it.projectId == projectId && it.key == reportKey }
        ?: ProjectReport(
            reportId = reports.maxOfOrNull { it.reportId }?.plus(1) ?: 1,
            projectId = projectId,
            key = reportKey,
            scope = scope,
            title = title.trim(),
            createdAt = createdAt,
        )
    require(report.scope == scope) { "Report scope cannot change" }
    val revisions = records.mapNotNull { it.revision }.filter { it.reportId == report.reportId }
    val existing = revisions.singleOrNull {
        it.sourceType == sourceType && it.sourceIdentity == sourceIdentity && it.sourceRevision == sourceRevision
    }
    if (existing != null) {
        require(existing.sourceHash == sourceHash) { "Report source identity has conflicting content" }
        val existingItems = records.single { it.revision == existing }.items
        return ReportPublication(report, existing, existingItems)
    }
    val revisionNumber = (revisions.maxOfOrNull { it.revision } ?: 0) + 1
    val items = itemInputs.map {
        ReportItem(report.reportId, revisionNumber, it.itemKey, it.kind, it.state, it.title.trim(), it.summary.trim(), it.actionRequired, it.evidence)
    }
    val unsigned = ReportRevision(
        report.reportId, revisionNumber, sourceType, sourceIdentity, sourceRevision, sourceHash,
        repositoryRevision, genesisRevision, state, createdAt, "",
    )
    val revision = unsigned.copy(contentHash = reportRevisionHash(unsigned, items))
    append(ProjectReportEvent(
        eventId = records.size + 1L,
        type = EVENT_PUBLICATION,
        report = report.takeIf { revisions.isEmpty() },
        revision = revision,
        items = items,
    ))
    return ReportPublication(report, revision, items)
}

private fun subscriptionInto(
    records: MutableList<ProjectReportEvent>,
    reportId: Long,
    mode: String,
    state: String,
    actor: String,
    createdAt: String,
    append: (ProjectReportEvent) -> Unit,
): ReportSubscription {
    val previous = records.mapNotNull { it.subscription }
        .filter { it.reportId == reportId && it.actor == actor }
        .maxByOrNull { it.revision }
    val unsigned = ReportSubscription(
        subscriptionId = records.mapNotNull { it.subscription }.maxOfOrNull { it.subscriptionId }?.plus(1) ?: 1,
        reportId = reportId,
        revision = (previous?.revision ?: 0) + 1,
        previousRevision = previous?.revision,
        mode = mode,
        state = state,
        actor = actor,
        createdAt = createdAt,
        hash = "",
    )
    val subscription = unsigned.copy(hash = subscriptionHash(unsigned))
    append(ProjectReportEvent(records.size + 1L, EVENT_SUBSCRIPTION, subscription = subscription))
    return subscription
}

private fun readInto(
    records: MutableList<ProjectReportEvent>,
    reportId: Long,
    reportRevision: Int,
    actor: String,
    readAt: String,
    append: (ProjectReportEvent) -> Unit,
): ReportRead {
    val existing = records.mapNotNull { it.read }.singleOrNull {
        it.reportId == reportId && it.reportRevision == reportRevision && it.actor == actor
    }
    if (existing != null) return existing
    val read = ReportRead(reportId, reportRevision, actor, readAt)
    append(ProjectReportEvent(records.size + 1L, EVENT_READ, read = read))
    return read
}

private fun threadInto(
    records: MutableList<ProjectReportEvent>,
    projectId: Int,
    targetType: String,
    targetId: Long,
    conversationId: Long,
    createdAt: String,
    append: (ProjectReportEvent) -> Unit,
): ReportThreadLink {
    val existing = records.mapNotNull { it.threadLink }.singleOrNull {
        it.projectId == projectId && it.targetType == targetType && it.targetId == targetId
    }
    if (existing != null) return existing
    val link = ReportThreadLink(
        threadLinkId = records.mapNotNull { it.threadLink }.maxOfOrNull { it.threadLinkId }?.plus(1) ?: 1,
        projectId = projectId,
        targetType = targetType,
        targetId = targetId,
        conversationId = conversationId,
        createdAt = createdAt,
    )
    append(ProjectReportEvent(records.size + 1L, EVENT_THREAD, threadLink = link))
    return link
}

private fun validateReportEvent(event: ProjectReportEvent, previous: List<ProjectReportEvent>) {
    require(event.eventId == previous.size + 1L) { "Project report event ID is not monotonic" }
    val reports = previous.mapNotNull { it.report } + listOfNotNull(event.report)
    require(reports.map { it.reportId }.distinct().size == reports.size) { "Project report ID is duplicated" }
    require(reports.map { it.projectId to it.key }.distinct().size == reports.size) { "Project report key is duplicated" }
    when (event.type) {
        EVENT_PUBLICATION -> {
            val revision = requireNotNull(event.revision) { "Publication has no revision" }
            val report = reports.singleOrNull { it.reportId == revision.reportId }
            require(report != null && report.projectId > 0 && report.key.isNotBlank() && report.title.isNotBlank()) {
                "Project report identity is invalid"
            }
            require(report.scope.type in REPORT_SCOPE_TYPES && report.scope.targetId.isNotBlank()) { "Report scope is invalid" }
            val priorRevisions = previous.mapNotNull { it.revision }.filter { it.reportId == revision.reportId }
            require(revision.revision == priorRevisions.size + 1) { "Report revision is not monotonic" }
            require(revision.sourceType.isNotBlank() && revision.sourceIdentity.isNotBlank() && revision.sourceRevision.isNotBlank()) {
                "Report source identity is incomplete"
            }
            require(revision.sourceHash.matches(SHA256)) { "Report source hash is invalid" }
            require(revision.repositoryRevision.isEmpty() || revision.repositoryRevision.matches(GIT_HASH)) {
                "Report repository revision is invalid"
            }
            require(revision.genesisRevision == null || revision.genesisRevision >= 0) { "Report genesis revision is invalid" }
            require(event.items.isNotEmpty() && event.items.map { it.itemKey }.distinct().size == event.items.size) {
                "Report items are empty or duplicated"
            }
            require(event.items.all {
                it.reportId == revision.reportId && it.reportRevision == revision.revision &&
                    it.itemKey.isNotBlank() && it.kind.isNotBlank() && it.state.isNotBlank() &&
                    it.title.isNotBlank() && it.summary.isNotBlank()
            }) { "Report item is invalid" }
            require(revision.contentHash == reportRevisionHash(revision, event.items)) { "Report revision content hash is invalid" }
            require((priorRevisions + revision).map {
                listOf(it.sourceType, it.sourceIdentity, it.sourceRevision).joinToString("\n")
            }.distinct().size == priorRevisions.size + 1) { "Report source revision is duplicated" }
        }
        EVENT_SUBSCRIPTION -> {
            val subscription = requireNotNull(event.subscription) { "Subscription event is empty" }
            require(reports.any { it.reportId == subscription.reportId }) { "Subscription report does not exist" }
            val prior = previous.mapNotNull { it.subscription }.filter {
                it.reportId == subscription.reportId && it.actor == subscription.actor
            }
            require(subscription.revision == prior.size + 1 && subscription.previousRevision == prior.lastOrNull()?.revision) {
                "Subscription successor is invalid"
            }
            require(subscription.mode in REPORT_MODES && subscription.state in REPORT_SUBSCRIPTION_STATES) {
                "Subscription mode or state is invalid"
            }
            require(subscription.actor.isNotBlank() && subscription.hash == subscriptionHash(subscription)) {
                "Subscription authority hash is invalid"
            }
        }
        EVENT_READ -> {
            val read = requireNotNull(event.read) { "Read event is empty" }
            require(previous.mapNotNull { it.revision }.any {
                it.reportId == read.reportId && it.revision == read.reportRevision
            }) { "Read report revision does not exist" }
            require(read.actor.isNotBlank()) { "Read actor is invalid" }
        }
        EVENT_THREAD -> {
            val link = requireNotNull(event.threadLink) { "Thread event is empty" }
            require(link.projectId > 0 && link.targetType in REPORT_TARGET_TYPES && link.targetId > 0 && link.conversationId > 0) {
                "Report thread link is invalid"
            }
            require(previous.mapNotNull { it.threadLink }.none {
                it.projectId == link.projectId && it.targetType == link.targetType && it.targetId == link.targetId
            }) { "Canonical report thread already exists" }
        }
        else -> error("Unsupported project report event type ${event.type}")
    }
}

private fun reportRevisionHash(revision: ReportRevision, items: List<ReportItem>): String = stagedPlanHash(
    authorityJson.encodeToString(ReportRevisionContent(revision.copy(contentHash = ""), items))
)

private fun subscriptionHash(subscription: ReportSubscription): String = stagedPlanHash(
    authorityJson.encodeToString(subscription.copy(hash = ""))
)

@Serializable
private data class ReportRevisionContent(val revision: ReportRevision, val items: List<ReportItem>)

@Serializable
private data class ProjectReportEnvelope(
    val version: Int = 1,
    val value: ProjectReportEvent,
    val checksum: String,
)

private const val EVENT_PUBLICATION = "PUBLICATION"
private const val EVENT_SUBSCRIPTION = "SUBSCRIPTION"
private const val EVENT_READ = "READ"
private const val EVENT_THREAD = "THREAD"
private val REPORT_SCOPE_TYPES = setOf(
    REPORT_SCOPE_PROJECT, REPORT_SCOPE_TICKET, REPORT_SCOPE_OUTCOME, REPORT_SCOPE_CAPABILITY, REPORT_SCOPE_REPOSITORY_AREA,
)
private val REPORT_MODES = setOf(REPORT_MODE_IMPORTANT, REPORT_MODE_MILESTONES, REPORT_MODE_ACTION_REQUIRED, REPORT_MODE_ALL)
private val REPORT_SUBSCRIPTION_STATES = setOf(REPORT_SUBSCRIPTION_ACTIVE, REPORT_SUBSCRIPTION_PAUSED, REPORT_SUBSCRIPTION_CLOSED)
private val REPORT_TARGET_TYPES = setOf(REPORT_TARGET_REPORT, REPORT_TARGET_TICKET)
private val SHA256 = Regex("[0-9a-f]{64}")
private val GIT_HASH = Regex("[0-9a-f]{40,64}")
private val authorityJson = Json { encodeDefaults = true }