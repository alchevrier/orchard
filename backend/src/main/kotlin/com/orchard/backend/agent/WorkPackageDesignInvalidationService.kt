package com.orchard.backend.agent

import com.orchard.backend.analysis.ExecutableWorkPackageStore
import com.orchard.backend.workspace.DESIGN_STATUS_ADMITTED
import com.orchard.backend.workspace.DesignAuthorityReference
import com.orchard.backend.workspace.WorkspaceStore
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

@Serializable
data class WorkPackageDesignInvalidation(
    val invalidationId: Long,
    val packageId: Long,
    val packageHash: String,
    val runId: Long,
    val supersededDesign: DesignAuthorityReference,
    val admittedSuccessorDesign: DesignAuthorityReference,
    val invalidatedAt: String = Instant.now().toString(),
    val hash: String,
)

interface WorkPackageDesignInvalidationStore {
    fun load(): List<WorkPackageDesignInvalidation>
    fun appendNext(create: (invalidationId: Long) -> WorkPackageDesignInvalidation): WorkPackageDesignInvalidation
}

class TransientWorkPackageDesignInvalidationStore : WorkPackageDesignInvalidationStore {
    private val invalidations = mutableListOf<WorkPackageDesignInvalidation>()

    @Synchronized
    override fun load(): List<WorkPackageDesignInvalidation> = invalidations.toList()

    @Synchronized
    override fun appendNext(create: (invalidationId: Long) -> WorkPackageDesignInvalidation): WorkPackageDesignInvalidation {
        val invalidation = create(invalidations.size + 1L)
        validateWorkPackageDesignInvalidation(invalidation, invalidations)
        invalidations += invalidation
        return invalidation
    }
}

class FileWorkPackageDesignInvalidationStore(private val directory: Path) : WorkPackageDesignInvalidationStore {
    private val path = directory.resolve("work-package-design-invalidations.jsonl")
    private val lockPath = directory.resolve("work-package-design-invalidations.lock")
    private val json = Json { encodeDefaults = true }

    @Synchronized
    override fun load(): List<WorkPackageDesignInvalidation> {
        Files.createDirectories(directory)
        return FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { lock ->
            lock.lock().use { loadUnlocked() }
        }
    }

    @Synchronized
    override fun appendNext(create: (invalidationId: Long) -> WorkPackageDesignInvalidation): WorkPackageDesignInvalidation {
        Files.createDirectories(directory)
        return FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { lock ->
            lock.lock().use {
                val invalidations = loadUnlocked()
                val invalidation = create(invalidations.size + 1L)
                validateWorkPackageDesignInvalidation(invalidation, invalidations)
                val payload = json.encodeToString(invalidation)
                val line = json.encodeToString(
                    WorkPackageDesignInvalidationEnvelope(value = invalidation, checksum = stagedPlanHash(payload)),
                ) + "\n"
                FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { channel ->
                    val bytes = ByteBuffer.wrap(line.toByteArray(Charsets.UTF_8))
                    while (bytes.hasRemaining()) channel.write(bytes)
                    channel.force(true)
                }
                FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
                invalidation
            }
        }
    }

    private fun loadUnlocked(): List<WorkPackageDesignInvalidation> = mutableListOf<WorkPackageDesignInvalidation>().also { invalidations ->
        loadRecoverableJsonl(path, "work-package-design-invalidations") { line, recordNumber ->
            val envelope = json.decodeFromString<WorkPackageDesignInvalidationEnvelope>(line)
            require(envelope.version == STORE_VERSION) { "Unsupported work-package design invalidation format ${envelope.version}" }
            require(envelope.checksum == stagedPlanHash(json.encodeToString(envelope.value))) {
                "Checksum mismatch in work-package design invalidation $recordNumber"
            }
            validateWorkPackageDesignInvalidation(envelope.value, invalidations)
            invalidations += envelope.value
            envelope.value
        }
    }
}

class WorkPackageDesignInvalidationService(
    private val workspace: WorkspaceStore,
    private val workPackageStore: ExecutableWorkPackageStore,
    private val invalidationStore: WorkPackageDesignInvalidationStore,
    private val pullRequestStore: CandidatePullRequestStore? = null,
    private val dispositionService: CandidatePullRequestDispositionService? = null,
) {
    @Synchronized
    fun invalidations(): List<WorkPackageDesignInvalidation> = invalidationStore.load()

    @Synchronized
    fun tick(): WorkPackageDesignInvalidation? {
        val admitted = workspace.snapshot(0).designRevisions
            .filter { it.status == DESIGN_STATUS_ADMITTED }
            .associateBy { it.design.workItemId }
        val existing = invalidationStore.load()
        return workPackageStore.load().asSequence().mapNotNull { packageAuthority ->
            val pinned = packageAuthority.design.admittedDesign ?: return@mapNotNull null
            val successorDesign = admitted[pinned.workItemId]?.design ?: return@mapNotNull null
            val successor = DesignAuthorityReference(
                successorDesign.workItemId,
                successorDesign.designId,
                successorDesign.revision,
                successorDesign.hash,
            )
            if (successor.hash == pinned.hash || existing.any {
                    it.packageHash == packageAuthority.hash && it.admittedSuccessorDesign.hash == successor.hash
                }) return@mapNotNull null
            val invalidation = invalidationStore.appendNext { invalidationId ->
                newWorkPackageDesignInvalidation(invalidationId, packageAuthority.packageId, packageAuthority.hash, packageAuthority.runId, pinned, successor)
            }
            pullRequestStore?.load()?.filter {
                it.workPackageId == packageAuthority.packageId && it.workPackageHash == packageAuthority.hash
            }?.forEach { pullRequest ->
                dispositionService?.record(
                    pullRequest.pullRequestId,
                    CANDIDATE_DISPOSITION_BLOCKED,
                    "The candidate package is superseded by admitted design ${successor.designId}.",
                )
            }
            invalidation
        }.firstOrNull()
    }
}

fun newWorkPackageDesignInvalidation(
    invalidationId: Long,
    packageId: Long,
    packageHash: String,
    runId: Long,
    supersededDesign: DesignAuthorityReference,
    admittedSuccessorDesign: DesignAuthorityReference,
): WorkPackageDesignInvalidation {
    val draft = WorkPackageDesignInvalidation(
        invalidationId,
        packageId,
        packageHash,
        runId,
        supersededDesign,
        admittedSuccessorDesign,
        hash = "",
    )
    return draft.copy(hash = workPackageDesignInvalidationHash(draft))
}

fun workPackageDesignInvalidationHash(invalidation: WorkPackageDesignInvalidation): String = stagedPlanHash(
    workPackageDesignInvalidationJson.encodeToString(invalidation.copy(hash = "")),
)

private fun validateWorkPackageDesignInvalidation(
    invalidation: WorkPackageDesignInvalidation,
    previous: List<WorkPackageDesignInvalidation>,
) {
    require(invalidation.invalidationId == previous.size + 1L && invalidation.packageId > 0 && invalidation.runId > 0 &&
        invalidation.packageHash.matches(SHA256) && invalidation.supersededDesign.hash.matches(SHA256) &&
        invalidation.admittedSuccessorDesign.hash.matches(SHA256) &&
        invalidation.supersededDesign.workItemId == invalidation.admittedSuccessorDesign.workItemId &&
        invalidation.supersededDesign.hash != invalidation.admittedSuccessorDesign.hash
    ) { "Work-package design invalidation is invalid" }
    require(previous.none {
        it.packageHash == invalidation.packageHash && it.admittedSuccessorDesign.hash == invalidation.admittedSuccessorDesign.hash
    }) { "Work-package design invalidation already exists" }
    require(invalidation.hash == workPackageDesignInvalidationHash(invalidation)) { "Work-package design invalidation hash is invalid" }
}

@Serializable
private data class WorkPackageDesignInvalidationEnvelope(
    val version: Int = STORE_VERSION,
    val value: WorkPackageDesignInvalidation,
    val checksum: String,
)

private const val STORE_VERSION = 1
private val workPackageDesignInvalidationJson = Json { encodeDefaults = true }
private val SHA256 = Regex("[0-9a-f]{64}")