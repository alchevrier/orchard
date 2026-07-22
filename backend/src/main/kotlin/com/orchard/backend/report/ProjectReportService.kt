package com.orchard.backend.report

import com.orchard.backend.analysis.CLAIM_CONTRADICTED
import com.orchard.backend.analysis.CLAIM_PARTIALLY_SUPPORTED
import com.orchard.backend.analysis.CLAIM_UNESTABLISHED
import com.orchard.backend.analysis.BASELINE_STAGE_DECISIONS
import com.orchard.backend.analysis.BASELINE_STAGE_DELIVERY
import com.orchard.backend.analysis.BASELINE_STAGE_STRUCTURE
import com.orchard.backend.analysis.BASELINE_STAGE_VERIFICATION
import com.orchard.backend.analysis.REPOSITORY_BASELINE_STAGES
import com.orchard.backend.analysis.RepositoryBaselineAnalysis
import com.orchard.backend.analysis.RepositoryObjectiveAssessment
import com.orchard.backend.conversation.ConversationConductorService
import com.orchard.backend.conversation.ConversationOperationStatus
import com.orchard.backend.conversation.CreateConversationRequest
import com.orchard.backend.workspace.ENTITY_PROJECT
import com.orchard.backend.workspace.RUN_STATE_CANCELLED
import com.orchard.backend.workspace.RUN_STATE_CONTEXT_READY
import com.orchard.backend.workspace.RUN_STATE_DONE
import com.orchard.backend.workspace.RUN_STATE_EVIDENCE_BLOCKED
import com.orchard.backend.workspace.RUN_STATE_EVIDENCE_PENDING
import com.orchard.backend.workspace.RepositoryBindingStore
import com.orchard.backend.workspace.WorkspaceEntity
import com.orchard.backend.workspace.WorkspaceStore
import com.orchard.backend.workspace.stagedPlanHash
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class CreateProjectReportRequest(
    val clientRequestId: String,
    val scope: ReportScope,
    val title: String,
    val items: List<ReportItemInput>,
    val actor: String = "HUMAN",
)

@Serializable
data class ReportSubscriptionRequest(
    val mode: String,
    val actor: String = "HUMAN",
)

@Serializable
data class ReportActorRequest(val actor: String = "HUMAN")

@Serializable
data class ReportThreadRequest(
    val targetType: String,
    val targetId: Long,
    val actor: String = "HUMAN",
)

@Serializable
data class ReportRevisionProjection(
    val report: ProjectReport,
    val revision: ReportRevision,
    val items: List<ReportItem>,
    val subscription: ReportSubscription? = null,
    val thread: ReportThreadLink? = null,
    val unread: Boolean,
    val actionRequired: Boolean,
    val subscribed: Boolean,
    val blocked: Boolean,
    val completed: Boolean,
)

@Serializable
data class ProjectReportFilterData(
    val total: Int,
    val unread: Int,
    val actionRequired: Int,
    val subscribed: Int,
    val blocked: Int,
    val completed: Int,
)

@Serializable
data class ProjectReportInbox(
    val projectId: Int,
    val reports: List<ReportRevisionProjection>,
    val filters: ProjectReportFilterData,
    val lastEventId: Long,
)

class ProjectReportService(
    private val workspace: WorkspaceStore,
    private val repositoryBindings: RepositoryBindingStore,
    private val store: ProjectReportStore,
    private val latestAssessment: (Int) -> RepositoryObjectiveAssessment? = { null },
    private val conversationConductor: ConversationConductorService? = null,
    private val now: () -> String = { Instant.now().toString() },
    private val latestBaselineAnalysis: ((Int) -> RepositoryBaselineAnalysis?)? = null,
) {
    private val nextBaselineAttemptAt = mutableMapOf<Int, Instant>()

    @Synchronized
    fun synchronizeRepositoryBaselines(): List<Int> {
        val projectIds = workspace.entities().filter { it.type == ENTITY_PROJECT }.mapTo(sortedSetOf()) { it.id }
        val eligible = repositoryBindings.views(projectIds).values.filter { it.available && !it.dirty }.mapTo(sortedSetOf()) { it.projectId }
        eligible.forEach(::synchronizeRepositoryBaseline)
        return eligible.filter { projectId ->
            latestBaselineAnalysis?.invoke(projectId)?.complete != true &&
                (latestBaselineAnalysis != null || latestAssessment(projectId) == null)
        }
    }

    @Synchronized
    fun synchronizeTicketReports(projectId: Int? = null): Int {
        val snapshot = workspace.snapshot(0)
        val entities = workspace.entities().associateBy { it.id }
        val runs = snapshot.workflowRuns.filter { projectId == null || it.context.projectId == projectId }
        runs.forEach { run ->
            val ticket = entities[run.context.workItemId] ?: return@forEach
            val sourceHash = stagedPlanHash(serviceJson.encodeToString(run))
            val reportState = when (run.state) {
                RUN_STATE_EVIDENCE_BLOCKED, RUN_STATE_CANCELLED -> REPORT_STATE_BLOCKED
                RUN_STATE_DONE -> REPORT_STATE_COMPLETED
                else -> REPORT_STATE_OPEN
            }
            val (itemTitle, summary) = when (run.state) {
                RUN_STATE_CONTEXT_READY -> "Repository context is ready" to
                    "Orchard is analyzing the revision-pinned repository context for this ticket."
                RUN_STATE_EVIDENCE_PENDING -> "Verification is in progress" to
                    "Implementation evidence is being verified against the ticket's acceptance gates."
                RUN_STATE_EVIDENCE_BLOCKED -> "Evidence blocked delivery" to
                    "The current candidate did not satisfy one or more evidence gates; Orchard will prepare governed repair."
                RUN_STATE_DONE -> "Ticket passed its evidence gates" to
                    "The workflow completed with accepted revision-pinned evidence."
                RUN_STATE_CANCELLED -> "Ticket delivery was cancelled" to
                    "The workflow is cancelled and requires a user decision before delivery can continue."
                else -> "Ticket state changed" to "The workflow is now ${run.state.lowercase().replace('_', ' ')}."
            }
            store.publish(
                projectId = run.context.projectId,
                reportKey = "ticket:${ticket.id}",
                scope = ReportScope(REPORT_SCOPE_TICKET, ticket.id.toString()),
                title = ticket.title,
                sourceType = "WORKFLOW_RUN",
                sourceIdentity = run.runId.toString(),
                sourceRevision = sourceHash,
                sourceHash = sourceHash,
                repositoryRevision = run.context.repository.commitHash,
                state = reportState,
                items = listOf(ReportItemInput(
                    itemKey = "workflow-state",
                    kind = "TICKET_PROGRESS",
                    state = reportState,
                    title = itemTitle,
                    summary = summary,
                    actionRequired = run.state == RUN_STATE_CANCELLED,
                    evidence = run.evidence.takeLast(MAX_REPORT_EVIDENCE).map { evidence ->
                        ReportEvidenceReference(
                            type = evidence.kind,
                            identity = evidence.evidenceId.toString(),
                            revision = evidence.revision,
                            hash = evidence.outputHash,
                            description = evidence.summary,
                        )
                    },
                )),
                createdAt = run.evidence.lastOrNull()?.recordedAt ?: run.createdAt,
            )
        }
        return runs.size
    }

    @Synchronized
    fun inbox(projectId: Int, actor: String = "HUMAN", requestedFilters: Set<String> = emptySet()): ProjectReportInbox {
        requireProject(projectId)
        synchronizeRepositoryBaseline(projectId)
        synchronizeTicketReports(projectId)
        val events = store.events()
        val reports = events.mapNotNull { it.report }.filter { it.projectId == projectId }.associateBy { it.reportId }
        val subscriptions = events.mapNotNull { it.subscription }.filter { it.actor == actor }.groupBy { it.reportId }
            .mapValues { (_, values) -> values.maxBy { it.revision } }
        val reads = events.mapNotNull { it.read }.filter { it.actor == actor }
            .map { it.reportId to it.reportRevision }.toSet()
        val threads = events.mapNotNull { it.threadLink }.filter { it.projectId == projectId }
            .associateBy { it.targetType to it.targetId }
        val projections = events.filter { it.revision?.reportId in reports }.map { event ->
            val revision = requireNotNull(event.revision)
            val report = reports.getValue(revision.reportId)
            val subscription = subscriptions[report.reportId]
            ReportRevisionProjection(
                report = report,
                revision = revision,
                items = event.items,
                subscription = subscription,
                thread = threads[REPORT_TARGET_REPORT to report.reportId] ?: report.scope.takeIf {
                    it.type == REPORT_SCOPE_TICKET
                }?.targetId?.toLongOrNull()?.let { threads[REPORT_TARGET_TICKET to it] },
                unread = (report.reportId to revision.revision) !in reads,
                actionRequired = event.items.any { it.actionRequired },
                subscribed = subscription?.state == REPORT_SUBSCRIPTION_ACTIVE,
                blocked = revision.state == REPORT_STATE_BLOCKED || event.items.any { it.state == REPORT_STATE_BLOCKED },
                completed = revision.state == REPORT_STATE_COMPLETED || event.items.all { it.state == REPORT_STATE_COMPLETED },
            )
        }.sortedWith(compareByDescending<ReportRevisionProjection> { it.revision.createdAt }.thenByDescending { it.revision.revision })
        val filterData = ProjectReportFilterData(
            total = projections.size,
            unread = projections.count { it.unread },
            actionRequired = projections.count { it.actionRequired },
            subscribed = projections.count { it.subscribed },
            blocked = projections.count { it.blocked },
            completed = projections.count { it.completed },
        )
        val filtered = projections.filter { projection ->
            requestedFilters.all { filter ->
                when (filter.lowercase()) {
                    "unread" -> projection.unread
                    "action-required" -> projection.actionRequired
                    "subscribed" -> projection.subscribed
                    "blocked" -> projection.blocked
                    "completed" -> projection.completed
                    else -> throw IllegalArgumentException("Unsupported report filter $filter")
                }
            }
        }
        return ProjectReportInbox(projectId, filtered, filterData, events.lastOrNull()?.eventId ?: 0)
    }

    @Synchronized
    fun create(projectId: Int, request: CreateProjectReportRequest): ReportPublication {
        requireProject(projectId)
        require(request.clientRequestId.isNotBlank() && request.actor.isNotBlank()) { "Report request identity is invalid" }
        require(request.title.isNotBlank() && request.items.isNotEmpty()) { "Report content is empty" }
        require(request.items.all { it.diagnosis == null && it.remediation == null }) {
            "Generated diagnosis and remediation metadata cannot be supplied by a user report"
        }
        validateScope(projectId, request.scope)
        val sourceIdentity = "${request.actor.trim()}:${request.clientRequestId.trim()}"
        val sourceHash = stagedPlanHash(serviceJson.encodeToString(request))
        return store.publish(
            projectId = projectId,
            reportKey = "user:$sourceIdentity",
            scope = request.scope,
            title = request.title,
            sourceType = "USER_REPORT",
            sourceIdentity = sourceIdentity,
            sourceRevision = "1",
            sourceHash = sourceHash,
            state = REPORT_STATE_OPEN,
            items = request.items,
            createdAt = now(),
        )
    }

    @Synchronized
    fun subscribe(projectId: Int, reportId: Long, request: ReportSubscriptionRequest): ReportSubscription {
        requireReport(projectId, reportId)
        require(request.actor.isNotBlank()) { "Subscription actor is invalid" }
        return successor(reportId, request.mode, REPORT_SUBSCRIPTION_ACTIVE, request.actor)
    }

    @Synchronized
    fun pause(projectId: Int, reportId: Long, request: ReportActorRequest): ReportSubscription {
        requireReport(projectId, reportId)
        val current = latestSubscription(reportId, request.actor) ?: throw IllegalArgumentException("Report is not subscribed")
        return successor(reportId, current.mode, REPORT_SUBSCRIPTION_PAUSED, request.actor)
    }

    @Synchronized
    fun resume(projectId: Int, reportId: Long, request: ReportActorRequest): ReportSubscription {
        requireReport(projectId, reportId)
        val current = latestSubscription(reportId, request.actor) ?: throw IllegalArgumentException("Report is not subscribed")
        return successor(reportId, current.mode, REPORT_SUBSCRIPTION_ACTIVE, request.actor)
    }

    @Synchronized
    fun markRead(projectId: Int, reportId: Long, revision: Int, request: ReportActorRequest): ReportRead {
        requireReport(projectId, reportId)
        require(request.actor.isNotBlank()) { "Read actor is invalid" }
        return store.markRead(reportId, revision, request.actor, now())
    }

    @Synchronized
    fun resolveThread(projectId: Int, request: ReportThreadRequest): ReportThreadLink {
        requireProject(projectId)
        require(request.actor.isNotBlank()) { "Thread actor is invalid" }
        val target = when (request.targetType) {
            REPORT_TARGET_REPORT -> requireReport(projectId, request.targetId).let { report ->
                if (report.scope.type == REPORT_SCOPE_TICKET) {
                    ReportThreadRequest(REPORT_TARGET_TICKET, report.scope.targetId.toLong(), request.actor)
                } else request
            }
            REPORT_TARGET_TICKET -> request.also { ensureTicketInboxEnvelope(projectId, request.targetId.toInt()) }
            else -> throw IllegalArgumentException("Unsupported report thread target")
        }
        store.events().mapNotNull { it.threadLink }.singleOrNull {
            it.projectId == projectId && it.targetType == target.targetType && it.targetId == target.targetId
        }?.let { return it }
        val conductor = requireNotNull(conversationConductor) { "Conversation service is unavailable" }
        val targetTitle = if (target.targetType == REPORT_TARGET_REPORT) {
            "Project $projectId report ${target.targetId}: ${requireReport(projectId, target.targetId).title}"
        } else {
            "Project $projectId ticket ${target.targetId}: ${requireTicket(projectId, target.targetId.toInt()).title}"
        }
        val created = conductor.create(CreateConversationRequest(targetTitle, target.actor))
        require(created.status == ConversationOperationStatus.CREATED) {
            created.diagnostic.ifBlank { "Conversation could not be created" }
        }
        val conversationId = requireNotNull(created.projection).conversation.conversationId
        return store.linkThread(projectId, target.targetType, target.targetId, conversationId, now())
    }

    private fun ensureTicketInboxEnvelope(projectId: Int, ticketId: Int) {
        val ticket = requireTicket(projectId, ticketId)
        val reportKey = "ticket:$ticketId"
        if (store.events().mapNotNull { it.report }.any { it.projectId == projectId && it.key == reportKey }) return
        val sourceHash = stagedPlanHash(serviceJson.encodeToString(ticket))
        store.publish(
            projectId = projectId,
            reportKey = reportKey,
            scope = ReportScope(REPORT_SCOPE_TICKET, ticketId.toString()),
            title = ticket.title,
            sourceType = "WORKSPACE_TICKET",
            sourceIdentity = ticketId.toString(),
            sourceRevision = sourceHash,
            sourceHash = sourceHash,
            state = REPORT_STATE_OPEN,
            items = listOf(ReportItemInput(
                itemKey = "ticket",
                kind = "TICKET",
                state = REPORT_STATE_OPEN,
                title = ticket.title,
                summary = ticket.content.ifBlank { ticket.title },
            )),
            createdAt = now(),
        )
    }

    private fun synchronizeRepositoryBaseline(projectId: Int) {
        val binding = repositoryBindings.views(setOf(projectId))[projectId] ?: return
        val repository = repositoryBindings.resolveHead(projectId)
        val reportKey = "repository-baseline"
        val sourceIdentity = "project:$projectId"
        val sourceRevision = repository?.commitHash ?: "binding:${stagedPlanHash(binding.path)}"
        val pendingHash = stagedPlanHash("$projectId\n${binding.path}\n$sourceRevision")
        store.publish(
            projectId = projectId,
            reportKey = reportKey,
            scope = ReportScope(REPORT_SCOPE_PROJECT, projectId.toString()),
            title = "Repository baseline",
            sourceType = "REPOSITORY_BASELINE_PENDING",
            sourceIdentity = sourceIdentity,
            sourceRevision = sourceRevision,
            sourceHash = pendingHash,
            repositoryRevision = repository?.commitHash.orEmpty(),
            state = REPORT_STATE_PENDING,
            items = listOf(ReportItemInput(
                itemKey = "repository-analysis",
                kind = "STATUS",
                state = REPORT_STATE_PENDING,
                title = "Repository assessment pending",
                summary = "Orchard is forming a revision-pinned understanding of the repository.",
            )),
            createdAt = now(),
        )
        val baseline = latestBaselineAnalysis?.invoke(projectId)
            ?.takeIf { it.repositoryRevision == repository?.commitHash }
        if (baseline != null) {
            val reportState = if (baseline.complete) REPORT_STATE_OPEN else REPORT_STATE_PENDING
            val completedStage = baseline.sections.last()
            val progress = ReportItemInput(
                itemKey = "baseline-progress",
                kind = "BASELINE_PROGRESS",
                state = reportState,
                title = if (baseline.complete) "Repository baseline complete" else
                    "${completedStage.stage.lowercase().replaceFirstChar(Char::uppercase)} analysis complete",
                summary = "${baseline.sections.size} of ${REPOSITORY_BASELINE_STAGES.size} repository analysis stages complete. " +
                    "${baseline.graphCoverage.contentAddressedFileCount} of ${baseline.graphCoverage.trackedFileCount} tracked files " +
                    "are content-addressed in the centralized intelligence graph. ${completedStage.summary}",
                evidence = listOf(ReportEvidenceReference(
                    type = "REPOSITORY_INTELLIGENCE_GRAPH",
                    identity = baseline.graphHash,
                    revision = baseline.repositoryRevision,
                    hash = baseline.graphHash,
                    description = "Revision-pinned graph with ${baseline.graphCoverage.nodeCount} nodes and " +
                        "${baseline.graphCoverage.edgeCount} correlation edges.",
                )),
            )
            val findings = baseline.sections.flatMap { section ->
                section.findings.map { finding ->
                    val diagnosis = finding.takeIf {
                        it.status in setOf(CLAIM_PARTIALLY_SUPPORTED, CLAIM_CONTRADICTED, CLAIM_UNESTABLISHED)
                    }?.let { baselineGapDiagnosis(section.stage, it.statement) }
                    ReportItemInput(
                        itemKey = finding.claimId,
                        kind = "REPOSITORY_${section.stage}_FINDING",
                        state = finding.status,
                        title = finding.statement,
                        summary = section.summary,
                        actionRequired = finding.status == CLAIM_CONTRADICTED,
                        evidence = finding.support.map { evidence ->
                            ReportEvidenceReference(
                                "REPOSITORY_SUPPORT",
                                evidence.path,
                                baseline.repositoryRevision,
                                evidence.contentHash,
                                evidence.observation,
                            )
                        } + finding.defeaters.map { evidence ->
                            ReportEvidenceReference(
                                "REPOSITORY_DEFEATER",
                                evidence.path,
                                baseline.repositoryRevision,
                                evidence.contentHash,
                                evidence.observation,
                            )
                        },
                        diagnosis = diagnosis,
                        remediation = diagnosis?.let {
                            baselineGapRemediation(
                                section.stage,
                                finding.claimId,
                                baseline.repositoryRevision,
                                it,
                            )
                        },
                    )
                } + section.unresolvedQuestions.mapIndexed { index, question ->
                    ReportItemInput(
                        itemKey = "${section.stage.lowercase()}-question-${index + 1}",
                        kind = "UNRESOLVED_QUESTION",
                        state = REPORT_STATE_OPEN,
                        title = question,
                        summary = "Repository evidence could not resolve this question during ${section.stage.lowercase()} analysis.",
                        actionRequired = true,
                    )
                }
            }
            store.publish(
                projectId = projectId,
                reportKey = reportKey,
                scope = ReportScope(REPORT_SCOPE_PROJECT, projectId.toString()),
                title = "Repository baseline",
                sourceType = "REPOSITORY_BASELINE_ANALYSIS",
                sourceIdentity = baseline.analysisId.toString(),
                sourceRevision = baseline.hash,
                sourceHash = baseline.hash,
                repositoryRevision = baseline.repositoryRevision,
                genesisRevision = baseline.genesisRevision,
                state = reportState,
                items = listOf(progress) + findings,
                createdAt = baseline.analyzedAt,
            )
            return
        }
        val assessment = latestAssessment(projectId)?.takeIf { it.repositoryRevision == repository?.commitHash } ?: return
        store.publish(
            projectId = projectId,
            reportKey = reportKey,
            scope = ReportScope(REPORT_SCOPE_PROJECT, projectId.toString()),
            title = "Repository baseline",
            sourceType = "REPOSITORY_OBJECTIVE_ASSESSMENT",
            sourceIdentity = assessment.assessmentId.toString(),
            sourceRevision = assessment.assessmentId.toString(),
            sourceHash = assessment.hash,
            repositoryRevision = assessment.repositoryRevision,
            genesisRevision = assessment.genesisRevision,
            state = REPORT_STATE_OPEN,
            items = assessment.claims.map { claim ->
                ReportItemInput(
                    itemKey = claim.claimId,
                    kind = "REPOSITORY_CLAIM",
                    state = claim.status,
                    title = claim.statement,
                    summary = "Repository assessment: ${claim.status.lowercase().replace('_', ' ')}.",
                    actionRequired = claim.status == CLAIM_CONTRADICTED,
                    evidence = claim.support.map { evidence ->
                        ReportEvidenceReference(
                            "REPOSITORY_SUPPORT", evidence.path, assessment.repositoryRevision,
                            evidence.contentHash, evidence.observation,
                        )
                    } + claim.defeaters.map { evidence ->
                        ReportEvidenceReference(
                            "REPOSITORY_DEFEATER", evidence.path, assessment.repositoryRevision,
                            evidence.contentHash, evidence.observation,
                        )
                    },
                )
            },
            createdAt = assessment.assessedAt,
        )
    }

    private fun baselineGapDiagnosis(stage: String, statement: String): ReportGapDiagnosis = when (stage) {
        BASELINE_STAGE_STRUCTURE -> ReportGapDiagnosis(
            category = "ARCHITECTURE_EVIDENCE",
            missing = statement,
            impact = "Outcome planning cannot reliably identify affected implementation boundaries until this is established.",
            suggestedEvidence = listOf(
                "Map affected modules and components to exact repository paths.",
                "Record dependency, API, persistence, or runtime boundary evidence.",
            ),
        )
        BASELINE_STAGE_DECISIONS -> ReportGapDiagnosis(
            category = "DECISION_EVIDENCE",
            missing = statement,
            impact = "Delivery may violate or duplicate an architectural decision until this is established.",
            suggestedEvidence = listOf(
                "Confirm or author an ADR with status, constraints, and consequences.",
                "Map the decision to affected components and repository paths.",
            ),
        )
        BASELINE_STAGE_VERIFICATION -> ReportGapDiagnosis(
            category = "TEST_EVIDENCE",
            missing = statement,
            impact = "Orchard cannot compile a defensible acceptance gate for this area until its verification method is established.",
            suggestedEvidence = listOf(
                "Identify the appropriate test level and test methodology.",
                "Add or locate executable tests, fixtures, and representative data.",
                "Record deterministic verification commands and the evidence they must produce.",
            ),
        )
        BASELINE_STAGE_DELIVERY -> ReportGapDiagnosis(
            category = "DELIVERY_EVIDENCE",
            missing = statement,
            impact = "Orchard cannot demonstrate safe, repeatable delivery for this area until operational evidence is established.",
            suggestedEvidence = listOf(
                "Identify CI, runtime, deployment, and observability verification.",
                "Record rollback, recovery, and production-safety evidence.",
            ),
        )
        else -> error("Unknown repository baseline stage $stage")
    }

    private fun baselineGapRemediation(
        stage: String,
        claimId: String,
        repositoryRevision: String,
        diagnosis: ReportGapDiagnosis,
    ): ReportRemediationAction {
        val label = when (stage) {
            BASELINE_STAGE_STRUCTURE -> "Plan architecture evidence"
            BASELINE_STAGE_DECISIONS -> "Plan decision evidence"
            BASELINE_STAGE_VERIFICATION -> "Plan test and evidence work"
            BASELINE_STAGE_DELIVERY -> "Plan delivery evidence"
            else -> error("Unknown repository baseline stage $stage")
        }
        return ReportRemediationAction(
            kind = "PLAN_GOVERNED_REMEDIATION",
            label = label,
            prompt = buildString {
                append("Diagnose and plan governed remediation for repository baseline gap ")
                append(claimId).append(" at revision ").append(repositoryRevision).append(". ")
                append("Category: ").append(diagnosis.category).append(". Missing: ").append(diagnosis.missing).append(". ")
                append("Identify the evidence to gather, appropriate tests and test methodology, acceptance criteria, ")
                append("deterministic verification commands, scope, non-goals, and unresolved user decisions. ")
                append("Use existing repository intelligence and do not mutate repository or project authority until the user admits exact work.")
            },
        )
    }

    private fun successor(reportId: Long, mode: String, state: String, actor: String): ReportSubscription {
        val current = latestSubscription(reportId, actor)
        if (current != null && current.mode == mode && current.state == state) return current
        return store.appendSubscription(reportId, mode, state, actor, now())
    }

    private fun latestSubscription(reportId: Long, actor: String): ReportSubscription? = store.events().mapNotNull { it.subscription }
        .filter { it.reportId == reportId && it.actor == actor }.maxByOrNull { it.revision }

    @Synchronized
    fun recordRepositoryBaselineDiagnostic(
        projectId: Int,
        status: String,
        diagnostic: String,
        actionRequired: Boolean,
    ) {
        requireProject(projectId)
        val binding = repositoryBindings.views(setOf(projectId))[projectId] ?: return
        val repository = repositoryBindings.resolveHead(projectId)
        val genesisRevision = workspace.snapshot(0).projectGenesis.singleOrNull { it.projectId == projectId }?.revision?.revision ?: 0
        val recordedAt = Instant.parse(now())
        val retryingForCapacity = status in setOf("BUSY", "RESOURCE_CAPACITY_UNAVAILABLE")
        val reportState = if (retryingForCapacity) REPORT_STATE_PENDING else REPORT_STATE_BLOCKED
        val title = if (status == "RESOURCE_CAPACITY_UNAVAILABLE") {
            "Retry when capacity is available"
        } else if (status == "BUSY") {
            "Repository baseline is waiting"
        } else {
            "Repository baseline is delayed"
        }
        val summary = if (retryingForCapacity) {
            "Orchard will retry the repository baseline automatically when Architect capacity is available. No action is needed."
        } else {
            diagnostic.ifBlank { "Repository baseline compilation is delayed with status $status." }
        }
        val sourceHash = stagedPlanHash(
            "$projectId\n${repository?.commitHash}\n$genesisRevision\n$status\n$summary"
        )
        store.publish(
            projectId = projectId,
            reportKey = "repository-baseline",
            scope = ReportScope(REPORT_SCOPE_PROJECT, projectId.toString()),
            title = "Repository baseline",
            sourceType = "REPOSITORY_BASELINE_DIAGNOSTIC",
            sourceIdentity = "project:$projectId",
            sourceRevision = sourceHash,
            sourceHash = sourceHash,
            repositoryRevision = repository?.commitHash.orEmpty(),
            genesisRevision = genesisRevision,
            state = reportState,
            items = listOf(ReportItemInput(
                itemKey = "baseline-$status",
                kind = "DIAGNOSTIC",
                state = reportState,
                title = title,
                summary = summary,
                actionRequired = actionRequired,
                evidence = listOf(ReportEvidenceReference("REPOSITORY_BINDING", binding.path)),
            )),
            createdAt = recordedAt.toString(),
        )
        nextBaselineAttemptAt[projectId] = recordedAt.plusSeconds(baselineRetrySeconds(status))
    }

    @Synchronized
    fun repositoryBaselineAttemptDue(projectId: Int): Boolean {
        val events = store.events()
        val report = events.mapNotNull { it.report }
            .singleOrNull { it.projectId == projectId && it.key == "repository-baseline" } ?: return true
        val diagnostic = events.mapNotNull { it.revision }
            .filter { it.reportId == report.reportId && it.sourceType == "REPOSITORY_BASELINE_DIAGNOSTIC" }
            .maxByOrNull { it.revision } ?: return true
        val repositoryRevision = repositoryBindings.resolveHead(projectId)?.commitHash.orEmpty()
        val genesisRevision = workspace.snapshot(0).projectGenesis.singleOrNull { it.projectId == projectId }?.revision?.revision ?: 0
        if (diagnostic.repositoryRevision != repositoryRevision || diagnostic.genesisRevision != genesisRevision) {
            nextBaselineAttemptAt.remove(projectId)
            return true
        }
        val status = events.singleOrNull {
            it.revision?.reportId == report.reportId && it.revision.revision == diagnostic.revision
        }?.items?.singleOrNull()?.itemKey?.removePrefix("baseline-").orEmpty()
        val currentTime = Instant.parse(now())
        val retryAt = nextBaselineAttemptAt[projectId]
            ?: Instant.parse(diagnostic.createdAt).plusSeconds(baselineRetrySeconds(status))
        return !currentTime.isBefore(retryAt)
    }

    private fun requireProject(projectId: Int): WorkspaceEntity = workspace.entities().singleOrNull {
        it.id == projectId && it.type == ENTITY_PROJECT
    } ?: throw IllegalArgumentException("Project does not exist")

    private fun requireReport(projectId: Int, reportId: Long): ProjectReport = store.events().mapNotNull { it.report }.singleOrNull {
        it.reportId == reportId && it.projectId == projectId
    } ?: throw IllegalArgumentException("Report does not belong to project")

    private fun validateScope(projectId: Int, scope: ReportScope) {
        when (scope.type) {
            REPORT_SCOPE_PROJECT -> require(scope.targetId == projectId.toString()) { "Project report scope is invalid" }
            REPORT_SCOPE_TICKET, REPORT_SCOPE_OUTCOME -> requireTicket(projectId, scope.targetId.toIntOrNull()
                ?: throw IllegalArgumentException("Ticket report scope is invalid"))
            REPORT_SCOPE_CAPABILITY, REPORT_SCOPE_REPOSITORY_AREA -> require(scope.targetId.isNotBlank()) { "Report scope is empty" }
            else -> throw IllegalArgumentException("Unsupported report scope")
        }
    }

    private fun requireTicket(projectId: Int, ticketId: Int): WorkspaceEntity {
        val entities = workspace.entities().associateBy { it.id }
        val ticket = entities[ticketId]?.takeIf { it.type != ENTITY_PROJECT }
            ?: throw IllegalArgumentException("Ticket does not exist")
        var current = ticket
        while (current.type != ENTITY_PROJECT) {
            current = entities[current.parentId] ?: throw IllegalArgumentException("Ticket hierarchy is invalid")
        }
        require(current.id == projectId) { "Ticket does not belong to project" }
        return ticket
    }

    private companion object {
        const val MAX_REPORT_EVIDENCE = 16
        const val REPORT_STATE_PENDING = "PENDING"
        const val REPORT_STATE_OPEN = "OPEN"
        const val REPORT_STATE_BLOCKED = "BLOCKED"
        const val REPORT_STATE_COMPLETED = "COMPLETED"
        fun baselineRetrySeconds(status: String): Long = when (status) {
            "BUSY", "RESOURCE_CAPACITY_UNAVAILABLE" -> 30
            "MODEL_UNAVAILABLE", "INVALID_OUTPUT" -> 900
            else -> 300
        }
        val serviceJson = Json { encodeDefaults = true }
    }
}