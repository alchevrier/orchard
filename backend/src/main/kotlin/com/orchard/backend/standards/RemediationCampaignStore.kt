package com.orchard.backend.standards

import com.orchard.backend.workspace.loadRecoverableJsonl
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val CAMPAIGN_ADMITTED = "ADMITTED"
const val CAMPAIGN_IN_PROGRESS = "IN_PROGRESS"
const val CAMPAIGN_VERIFYING = "VERIFYING"
const val CAMPAIGN_CLOSED = "CLOSED"
const val CAMPAIGN_BLOCKED = "BLOCKED"
const val CAMPAIGN_ESCALATED = "ESCALATED"

@Serializable
data class CampaignPracticeLink(
    val practiceId: String,
    val seedFindingId: String,
    val backlogNodeIds: List<String>,
    val admittedEntityIds: List<Int>,
)

@Serializable
data class CampaignSeedPractice(
    val practiceId: String,
    val findingId: String,
    val disposition: String,
)

@Serializable
data class RemediationCampaign(
    val campaignId: Long,
    val projectId: Int,
    val standardId: Long,
    val standardRevision: Int,
    val standardHash: String,
    val seedScanId: Long,
    val seedScanHash: String,
    val seedAdmissionId: Long,
    val seedAdmissionHash: String,
    val seedRepositoryRevision: String,
    val seedPractices: List<CampaignSeedPractice>,
    val links: List<CampaignPracticeLink>,
    val createdAt: String,
    val hash: String,
)

@Serializable
data class CampaignPracticeEvaluation(
    val practiceId: String,
    val priorDisposition: String,
    val currentDisposition: String,
    val resolved: Boolean,
    val regressed: Boolean,
)

@Serializable
data class RemediationCampaignEvaluation(
    val evaluationId: Long,
    val campaignId: Long,
    val scanId: Long,
    val scanHash: String,
    val repositoryRevision: String,
    val promotionIds: List<Long>,
    val practices: List<CampaignPracticeEvaluation>,
    val state: String,
    val idempotencyKey: String,
    val recordedAt: String,
    val hash: String,
)

@Serializable
data class RemediationCampaignView(
    val campaign: RemediationCampaign,
    val evaluations: List<RemediationCampaignEvaluation>,
    val state: String,
)

@Serializable
private data class RemediationCampaignEnvelope(
    val version: Int = 1,
    val campaign: RemediationCampaign? = null,
    val evaluation: RemediationCampaignEvaluation? = null,
    val checksum: String,
)

interface RemediationCampaignStore {
    fun campaigns(): List<RemediationCampaign>
    fun evaluations(): List<RemediationCampaignEvaluation>
    fun appendCampaign(campaign: RemediationCampaign)
    fun appendEvaluation(evaluation: RemediationCampaignEvaluation)
}

class TransientRemediationCampaignStore : RemediationCampaignStore {
    private val campaignRecords = mutableListOf<RemediationCampaign>()
    private val evaluationRecords = mutableListOf<RemediationCampaignEvaluation>()

    @Synchronized
    override fun campaigns(): List<RemediationCampaign> = campaignRecords.toList()

    @Synchronized
    override fun evaluations(): List<RemediationCampaignEvaluation> = evaluationRecords.toList()

    @Synchronized
    override fun appendCampaign(campaign: RemediationCampaign) {
        validateCampaignAppend(campaignRecords, campaign)
        campaignRecords += campaign
    }

    @Synchronized
    override fun appendEvaluation(evaluation: RemediationCampaignEvaluation) {
        validateEvaluationAppend(campaignRecords, evaluationRecords, evaluation)
        evaluationRecords += evaluation
    }
}

class FileRemediationCampaignStore(private val directory: Path) : RemediationCampaignStore {
    private val path = directory.resolve("remediation-campaigns.jsonl")
    private val lockPath = directory.resolve("remediation-campaigns.lock")
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    @Synchronized
    override fun campaigns(): List<RemediationCampaign> = load().mapNotNull { it.campaign }

    @Synchronized
    override fun evaluations(): List<RemediationCampaignEvaluation> = load().mapNotNull { it.evaluation }

    @Synchronized
    override fun appendCampaign(campaign: RemediationCampaign) = append(campaign = campaign)

    @Synchronized
    override fun appendEvaluation(evaluation: RemediationCampaignEvaluation) = append(evaluation = evaluation)

    private fun append(
        campaign: RemediationCampaign? = null,
        evaluation: RemediationCampaignEvaluation? = null,
    ) {
        Files.createDirectories(directory)
        FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { lockChannel ->
            lockChannel.lock().use {
                val records = load()
                val campaigns = records.mapNotNull { it.campaign }
                val evaluations = records.mapNotNull { it.evaluation }
                campaign?.let { validateCampaignAppend(campaigns, it) }
                evaluation?.let { validateEvaluationAppend(campaigns, evaluations, it) }
                val payload = campaign?.let(json::encodeToString) ?: json.encodeToString(requireNotNull(evaluation))
                val envelope = RemediationCampaignEnvelope(
                    campaign = campaign,
                    evaluation = evaluation,
                    checksum = sha256(payload),
                )
                FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { channel ->
                    val bytes = ByteBuffer.wrap((json.encodeToString(envelope) + "\n").toByteArray(Charsets.UTF_8))
                    while (bytes.hasRemaining()) channel.write(bytes)
                    channel.force(true)
                }
            }
        }
    }

    private fun load(): List<RemediationCampaignEnvelope> = loadRecoverableJsonl(path, "remediation campaigns") { line, _ ->
        val envelope = json.decodeFromString<RemediationCampaignEnvelope>(line)
        require(envelope.version == 1 && listOfNotNull(envelope.campaign, envelope.evaluation).size == 1) {
            "Remediation campaign record shape is invalid"
        }
        val payload = envelope.campaign?.let(json::encodeToString) ?: json.encodeToString(requireNotNull(envelope.evaluation))
        require(envelope.checksum == sha256(payload)) { "Remediation campaign checksum mismatch" }
        envelope
    }.also { records ->
        val campaigns = mutableListOf<RemediationCampaign>()
        val evaluations = mutableListOf<RemediationCampaignEvaluation>()
        records.forEach { record ->
            record.campaign?.let { validateCampaignAppend(campaigns, it); campaigns += it }
            record.evaluation?.let { validateEvaluationAppend(campaigns, evaluations, it); evaluations += it }
        }
    }
}

fun newRemediationCampaign(campaign: RemediationCampaign): RemediationCampaign =
    campaign.copy(hash = remediationCampaignHash(campaign.copy(hash = "")))

fun newRemediationCampaignEvaluation(evaluation: RemediationCampaignEvaluation): RemediationCampaignEvaluation =
    evaluation.copy(hash = remediationCampaignEvaluationHash(evaluation.copy(hash = "")))

fun remediationCampaignViews(store: RemediationCampaignStore, projectId: Int? = null): List<RemediationCampaignView> {
    val evaluations = store.evaluations().groupBy { it.campaignId }
    return store.campaigns().filter { projectId == null || it.projectId == projectId }.map { campaign ->
        val campaignEvaluations = evaluations[campaign.campaignId].orEmpty().sortedBy { it.evaluationId }
        RemediationCampaignView(campaign, campaignEvaluations, campaignEvaluations.lastOrNull()?.state ?: CAMPAIGN_ADMITTED)
    }
}

private fun validateCampaignAppend(existing: List<RemediationCampaign>, campaign: RemediationCampaign) {
    require(campaign.campaignId > 0 && campaign.projectId > 0 && campaign.standardId > 0 && campaign.standardRevision > 0) {
        "Remediation campaign identity is invalid"
    }
    require(campaign.standardHash.matches(SHA256) && campaign.seedScanHash.matches(SHA256) &&
        campaign.seedAdmissionHash.matches(SHA256) && campaign.seedRepositoryRevision.matches(GIT_REVISION)) {
        "Remediation campaign authority references are invalid"
    }
    require(campaign.seedScanId > 0 && campaign.seedAdmissionId > 0 && campaign.createdAt.isNotBlank()) {
        "Remediation campaign source is invalid"
    }
    require(existing.none { it.seedAdmissionId == campaign.seedAdmissionId || it.campaignId == campaign.campaignId }) {
        "Remediation campaign already exists"
    }
    require(campaign.links.isNotEmpty() && campaign.links.map { it.practiceId }.distinct().size == campaign.links.size &&
        campaign.links.all(::validLink)) { "Remediation campaign links are invalid" }
    require(campaign.seedPractices.isNotEmpty() &&
        campaign.seedPractices.map { it.practiceId }.distinct().size == campaign.seedPractices.size &&
        campaign.seedPractices.all {
            it.practiceId.matches(Regex("[A-Z][A-Z0-9_-]{2,63}")) && it.findingId.isNotBlank() &&
                it.disposition in CONFORMANCE_DISPOSITIONS
        } && campaign.links.all { link -> campaign.seedPractices.any { it.practiceId == link.practiceId } }) {
        "Remediation campaign seed practices are invalid"
    }
    require(campaign.hash == remediationCampaignHash(campaign.copy(hash = ""))) { "Remediation campaign hash is invalid" }
}

private fun validateEvaluationAppend(
    campaigns: List<RemediationCampaign>,
    existing: List<RemediationCampaignEvaluation>,
    evaluation: RemediationCampaignEvaluation,
) {
    val campaign = campaigns.singleOrNull { it.campaignId == evaluation.campaignId }
    require(campaign != null) { "Remediation evaluation campaign does not exist" }
    require(evaluation.evaluationId > 0 && evaluation.scanId > 0 && evaluation.scanHash.matches(SHA256) &&
        evaluation.repositoryRevision.matches(GIT_REVISION) && evaluation.recordedAt.isNotBlank()) {
        "Remediation evaluation identity is invalid"
    }
    require(evaluation.state in CAMPAIGN_STATES && evaluation.idempotencyKey == campaignIdempotencyKey(campaign.campaignId, evaluation.repositoryRevision)) {
        "Remediation evaluation state or idempotency key is invalid"
    }
    require(existing.none { it.idempotencyKey == evaluation.idempotencyKey || it.evaluationId == evaluation.evaluationId }) {
        "Remediation campaign revision is already evaluated"
    }
    require(evaluation.promotionIds.distinct().size == evaluation.promotionIds.size && evaluation.promotionIds.all { it > 0 }) {
        "Remediation evaluation promotions are invalid"
    }
    require(evaluation.practices.map { it.practiceId }.toSet() == campaign.seedPractices.map { it.practiceId }.toSet() &&
        evaluation.practices.size == campaign.seedPractices.size && evaluation.practices.all(::validPracticeEvaluation)) {
        "Remediation evaluation practices are invalid"
    }
    val linkedPracticeIds = campaign.links.map { it.practiceId }.toSet()
    val linkedEvaluations = evaluation.practices.filter { it.practiceId in linkedPracticeIds }
    val shouldClose = linkedEvaluations.all { it.resolved } && evaluation.practices.none { it.regressed }
    require((evaluation.state == CAMPAIGN_CLOSED) == shouldClose) {
        "Closed campaigns require every remediation practice to be resolved without regression"
    }
    require(evaluation.state != CAMPAIGN_ESCALATED || evaluation.practices.any { it.regressed } ||
        linkedEvaluations.any { it.currentDisposition in setOf(CONFORMANCE_UNKNOWN, CONFORMANCE_CONFLICTING) }) {
        "Escalated campaigns require uncertainty, conflict, or regression"
    }
    require(evaluation.hash == remediationCampaignEvaluationHash(evaluation.copy(hash = ""))) {
        "Remediation evaluation hash is invalid"
    }
}

private fun validLink(link: CampaignPracticeLink): Boolean =
    link.practiceId.matches(Regex("[A-Z][A-Z0-9_-]{2,63}")) && link.seedFindingId.isNotBlank() &&
        link.backlogNodeIds.isNotEmpty() && link.backlogNodeIds.distinct().size == link.backlogNodeIds.size &&
        link.admittedEntityIds.isNotEmpty() && link.admittedEntityIds.distinct().size == link.admittedEntityIds.size

private fun validPracticeEvaluation(evaluation: CampaignPracticeEvaluation): Boolean =
    evaluation.practiceId.matches(Regex("[A-Z][A-Z0-9_-]{2,63}")) &&
        evaluation.priorDisposition in CONFORMANCE_DISPOSITIONS && evaluation.currentDisposition in CONFORMANCE_DISPOSITIONS &&
        evaluation.resolved == (evaluation.currentDisposition in RESOLVED_DISPOSITIONS) &&
        (!evaluation.regressed || evaluation.priorDisposition in RESOLVED_DISPOSITIONS && evaluation.currentDisposition !in RESOLVED_DISPOSITIONS)

fun campaignIdempotencyKey(campaignId: Long, repositoryRevision: String): String = sha256("$campaignId:$repositoryRevision")

private fun remediationCampaignHash(campaign: RemediationCampaign): String = sha256(Json.encodeToString(campaign))
private fun remediationCampaignEvaluationHash(evaluation: RemediationCampaignEvaluation): String = sha256(Json.encodeToString(evaluation))
private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

private val SHA256 = Regex("[0-9a-f]{64}")
private val GIT_REVISION = Regex("[0-9a-f]{40,64}")
private val CAMPAIGN_STATES = setOf(
    CAMPAIGN_ADMITTED,
    CAMPAIGN_IN_PROGRESS,
    CAMPAIGN_VERIFYING,
    CAMPAIGN_CLOSED,
    CAMPAIGN_BLOCKED,
    CAMPAIGN_ESCALATED,
)
private val CONFORMANCE_DISPOSITIONS = setOf(
    CONFORMANCE_CONFORMING,
    CONFORMANCE_NONCONFORMING,
    CONFORMANCE_PARTIAL,
    CONFORMANCE_NOT_APPLICABLE,
    CONFORMANCE_UNKNOWN,
    CONFORMANCE_CONFLICTING,
    CONFORMANCE_EXCEPTION_ACTIVE,
)
private val RESOLVED_DISPOSITIONS = setOf(
    CONFORMANCE_CONFORMING,
    CONFORMANCE_NOT_APPLICABLE,
    CONFORMANCE_EXCEPTION_ACTIVE,
)
