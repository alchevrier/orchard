@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.orchard.backend.attention

import com.orchard.backend.analysis.ExecutableWorkPackage
import com.orchard.backend.analysis.PLAN_OPERATION_CREATE
import com.orchard.backend.analysis.PLAN_OPERATION_DELETE
import com.orchard.backend.analysis.PLAN_OPERATION_MODIFY
import com.orchard.backend.analysis.RepositoryExecutionPlan
import com.orchard.backend.analysis.WorkPackageCheck
import com.orchard.backend.analysis.WorkPackageEvidenceCitation
import com.orchard.backend.workspace.ProjectGenesisRevision
import com.orchard.backend.workspace.stagedPlanHash
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val ATTENTION_FRAME_VERSION = 1
const val ATTENTION_DISPOSITION_ACTIONABLE = "ACTIONABLE"
const val ATTENTION_DISPOSITION_EVIDENCE_ONLY = "EVIDENCE_ONLY"

@Serializable
enum class AttentionScopeKind {
    IMPLEMENTATION,
    PERSISTENCE,
    API,
    USER_INTERFACE,
    MIGRATION,
    VERIFICATION,
    DOCUMENTATION,
    OPERATIONAL,
    SECURITY,
}

@Serializable
data class AttentionAuthorityReference(
    val kind: String,
    val id: String,
    val sourceHash: String,
    val text: String,
)

@Serializable
data class AttentionSlice(
    val activeOperationOrders: List<Int>,
    val prerequisiteOperationOrders: List<Int>,
    val deferredOperationOrders: List<Int>,
    val completesObjective: Boolean,
)

@Serializable
data class TaskCodeCorrelation(
    val requirement: AttentionAuthorityReference,
    val scope: AttentionAuthorityReference,
    val scopeKind: AttentionScopeKind,
    val disposition: String,
    val operationOrder: Int? = null,
    val ownerPaths: List<String>,
    val allowedActions: List<String>,
    val evidence: List<WorkPackageEvidenceCitation>,
    val expectedBehavior: List<String>,
    val verificationCriteria: List<String>,
)

@Serializable
data class AttentionFrame(
    val formatVersion: Int = ATTENTION_FRAME_VERSION,
    val workflowStepId: String,
    val workItemId: Int,
    val repositoryRevision: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val projectPurpose: AttentionAuthorityReference? = null,
    val objective: AttentionAuthorityReference,
    val executionPlanId: Long,
    val executionPlanHash: String,
    val workPackageId: Long,
    val workPackageHash: String,
    val slice: AttentionSlice,
    val correlations: List<TaskCodeCorrelation>,
    val constraints: List<String>,
    val invariants: List<String>,
    val nonGoals: List<String>,
    val ownershipPaths: List<String>,
    val allowedActions: List<String>,
    val checks: List<WorkPackageCheck>,
    val escalationConditions: List<String>,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val hash: String = "",
)

@Serializable
data class AttentionAdequacyReport(
    val adequate: Boolean,
    val diagnostics: List<String>,
)

data class AttentionProposedOperation(
    val action: String,
    val path: String,
)

fun compileCodingAttentionFrame(
    workflowStepId: String,
    workItemId: Int,
    plan: RepositoryExecutionPlan,
    workPackage: ExecutableWorkPackage,
    scopeKinds: Map<Int, AttentionScopeKind>,
    projectPurpose: ProjectGenesisRevision? = null,
): AttentionFrame {
    require(projectPurpose == null || projectPurpose.admitted) { "Attention project purpose must be admitted." }
    val activeOrders = workPackage.operations.operations.map { it.order }.distinct().sorted()
    val activeOrderSet = activeOrders.toSet()
    val planOperations = plan.content.operations.associateBy { it.order }
    val prerequisites = activeOrders.flatMap { planOperations[it]?.dependsOnOrders.orEmpty() }
        .filter { it !in activeOrderSet }
        .distinct()
        .sorted()
    val deferredOrders = plan.content.operations.asSequence()
        .filter { it.action != "VERIFY" && it.order !in activeOrderSet }
        .map { it.order }
        .distinct()
        .sorted()
        .toList()
    val actionableCorrelations = activeOrders.flatMap { order ->
        val operation = planOperations[order] ?: return@flatMap emptyList()
        plan.content.scopeCoverage.withIndex().filter { (_, coverage) -> order in coverage.operationOrders }.map { (index, coverage) ->
            val requirement = AttentionAuthorityReference(
                kind = "WORK_DEFINITION_SCOPE",
                id = "scope-${index + 1}",
                sourceHash = workPackage.intent.definitionHash,
                text = coverage.scope,
            )
            TaskCodeCorrelation(
                requirement = requirement,
                scope = AttentionAuthorityReference(
                    kind = "EXECUTION_PLAN_SCOPE",
                    id = "coverage-${index + 1}",
                    sourceHash = plan.hash,
                    text = coverage.scope,
                ),
                scopeKind = requireNotNull(scopeKinds[index]) { "Attention scope ${index + 1} has no admitted kind." },
                disposition = ATTENTION_DISPOSITION_ACTIONABLE,
                operationOrder = order,
                ownerPaths = listOf(operation.path),
                allowedActions = boundedActions(operation.action),
                evidence = workPackage.evidence.citations.filter { it.path in coverage.evidencePaths },
                expectedBehavior = operation.acceptanceCriteria.distinct(),
                verificationCriteria = operation.acceptanceCriteria.distinct(),
            )
        }
    }
    val evidenceOnlyCorrelations = plan.content.scopeCoverage.withIndex().mapNotNull { (index, coverage) ->
        val evidence = workPackage.evidence.citations.filter {
            it.compliantReadOnly && it.path in coverage.compliantEvidencePaths
        }
        if (evidence.isEmpty() || coverage.operationOrders.any { it in activeOrderSet }) return@mapNotNull null
        TaskCodeCorrelation(
            requirement = AttentionAuthorityReference(
                kind = "WORK_DEFINITION_SCOPE",
                id = "scope-${index + 1}",
                sourceHash = workPackage.intent.definitionHash,
                text = coverage.scope,
            ),
            scope = AttentionAuthorityReference(
                kind = "EXECUTION_PLAN_SCOPE",
                id = "coverage-${index + 1}",
                sourceHash = plan.hash,
                text = coverage.scope,
            ),
            scopeKind = requireNotNull(scopeKinds[index]) { "Attention scope ${index + 1} has no admitted kind." },
            disposition = ATTENTION_DISPOSITION_EVIDENCE_ONLY,
            ownerPaths = evidence.map { it.path }.distinct().sorted(),
            allowedActions = emptyList(),
            evidence = evidence,
            expectedBehavior = workPackage.expectedBehavior.distinct(),
            verificationCriteria = workPackage.intent.acceptanceCriteria.distinct(),
        )
    }
    val correlations = actionableCorrelations + evidenceOnlyCorrelations
    val draft = AttentionFrame(
        workflowStepId = workflowStepId,
        workItemId = workItemId,
        repositoryRevision = workPackage.repositoryRevision,
        projectPurpose = projectPurpose?.let {
            AttentionAuthorityReference(
                kind = "PROJECT_PURPOSE",
                id = it.genesisId.toString(),
                sourceHash = it.hash,
                text = it.productIntent,
            )
        },
        objective = AttentionAuthorityReference(
            kind = "WORK_DEFINITION",
            id = workPackage.intent.definitionId.toString(),
            sourceHash = workPackage.intent.definitionHash,
            text = workPackage.intent.requestedOutcome,
        ),
        executionPlanId = plan.planId,
        executionPlanHash = plan.hash,
        workPackageId = workPackage.packageId,
        workPackageHash = workPackage.hash,
        slice = AttentionSlice(activeOrders, prerequisites, deferredOrders, deferredOrders.isEmpty()),
        correlations = correlations,
        constraints = workPackage.intent.constraints.distinct(),
        invariants = workPackage.design.preservedInvariants.distinct(),
        nonGoals = workPackage.design.nonGoals.distinct(),
        ownershipPaths = workPackage.ownership.paths.distinct().sorted(),
        allowedActions = workPackage.ownership.allowedActions.distinct().sorted(),
        checks = workPackage.checks.distinctBy { it.checkId },
        escalationConditions = workPackage.escalationConditions.distinct().sorted(),
    )
    return draft.copy(hash = attentionFrameHash(draft))
}

fun verifyCodingAttentionFrame(
    frame: AttentionFrame,
    plan: RepositoryExecutionPlan,
    workPackage: ExecutableWorkPackage,
    projectPurpose: ProjectGenesisRevision? = null,
): AttentionAdequacyReport {
    val diagnostics = buildList {
        if (frame.formatVersion != ATTENTION_FRAME_VERSION) add("Unsupported attention-frame version ${frame.formatVersion}.")
        if (frame.executionPlanId != plan.planId || frame.executionPlanHash != plan.hash) {
            add("Attention frame does not match the accepted execution plan.")
        }
        if (frame.workPackageId != workPackage.packageId || frame.workPackageHash != workPackage.hash) {
            add("Attention frame does not match the executable work package.")
        }
        if (frame.repositoryRevision != workPackage.repositoryRevision) add("Attention frame repository revision is stale.")
        val expectedPurpose = projectPurpose?.let {
            AttentionAuthorityReference("PROJECT_PURPOSE", it.genesisId.toString(), it.hash, it.productIntent)
        }
        if (frame.projectPurpose != expectedPurpose) add("Attention frame does not match admitted project purpose.")
        if (frame.objective != AttentionAuthorityReference(
                kind = "WORK_DEFINITION",
                id = workPackage.intent.definitionId.toString(),
                sourceHash = workPackage.intent.definitionHash,
                text = workPackage.intent.requestedOutcome,
            )
        ) add("Attention frame objective does not match admitted intent.")
        if (frame.ownershipPaths != workPackage.ownership.paths.distinct().sorted() ||
            frame.allowedActions != workPackage.ownership.allowedActions.distinct().sorted()
        ) {
            add("Attention frame changes executable ownership or action authority.")
        }
        if (frame.checks != workPackage.checks.distinctBy { it.checkId }) add("Attention frame changes verification authority.")
        val activeOrders = workPackage.operations.operations.mapTo(linkedSetOf()) { it.order }
        if (frame.slice.activeOperationOrders.toSet() != activeOrders) add("Attention frame active slice does not match package operations.")
        val deferredOrders = plan.content.operations.filter { it.action != "VERIFY" && it.order !in activeOrders }.mapTo(linkedSetOf()) { it.order }
        if (frame.slice.deferredOperationOrders.toSet() != deferredOrders) add("Attention frame does not identify every deferred operation.")
        if (frame.slice.completesObjective != deferredOrders.isEmpty()) add("Attention frame misstates objective completion.")
        val actionable = frame.correlations.filter { it.disposition == ATTENTION_DISPOSITION_ACTIONABLE }
        val evidenceOnly = frame.correlations.filter { it.disposition == ATTENTION_DISPOSITION_EVIDENCE_ONLY }
        val correlationOrders = actionable.mapNotNullTo(linkedSetOf()) { it.operationOrder }
        val orphanOperations = activeOrders - correlationOrders
        if (orphanOperations.isNotEmpty()) add("Active operations lack task correlation: ${orphanOperations.sorted().joinToString()}.")
        val foreignOperations = correlationOrders - activeOrders
        if (foreignOperations.isNotEmpty()) add("Task correlations grant inactive operations: ${foreignOperations.sorted().joinToString()}.")
        actionable.forEach { correlation ->
            val operation = plan.content.operations.singleOrNull { it.order == correlation.operationOrder }
            if (operation == null || correlation.ownerPaths != listOf(operation.path)) {
                add("Correlation ${correlation.operationOrder} does not match its accepted code owner.")
            }
            if (correlation.ownerPaths.any { it !in workPackage.ownership.paths }) {
                add("Correlation ${correlation.operationOrder} exceeds work-package ownership.")
            }
            if (operation != null && correlation.allowedActions != boundedActions(operation.action)) {
                add("Correlation ${correlation.operationOrder} changes path-specific action authority.")
            }
            if (correlation.verificationCriteria.isEmpty()) {
                add("Correlation ${correlation.operationOrder} has no verification criterion.")
            }
            if (correlation.requirement.sourceHash != workPackage.intent.definitionHash ||
                correlation.scope.sourceHash != plan.hash
            ) {
                add("Correlation ${correlation.operationOrder} does not pin its source authority.")
            }
        }
        evidenceOnly.forEach { correlation ->
            if (correlation.operationOrder != null || correlation.ownerPaths.isEmpty() || correlation.allowedActions.isNotEmpty() ||
                correlation.evidence.isEmpty() || correlation.evidence.any { !it.compliantReadOnly || it.path !in correlation.ownerPaths }
            ) {
                add("Evidence-only correlation ${correlation.scope.id} is not supported by compliant evidence.")
            }
            if (correlation.verificationCriteria.isEmpty()) {
                add("Evidence-only correlation ${correlation.scope.id} has no verification criterion.")
            }
        }
        val representedScopeIds = frame.correlations.mapTo(hashSetOf()) { it.scope.id }
        plan.content.scopeCoverage.withIndex().filter { (_, coverage) ->
            coverage.operationOrders.any { it in activeOrders } || coverage.compliantEvidencePaths.any { path ->
                workPackage.evidence.citations.any { it.path == path && it.compliantReadOnly }
            }
        }.forEach { (index, _) ->
            if ("coverage-${index + 1}" !in representedScopeIds) add("Active scope ${index + 1} lacks task-code correlation.")
        }
        if (frame.constraints != workPackage.intent.constraints.distinct() ||
            frame.invariants != workPackage.design.preservedInvariants.distinct() ||
            frame.nonGoals != workPackage.design.nonGoals.distinct() ||
            frame.escalationConditions != workPackage.escalationConditions.distinct().sorted()
        ) {
            add("Attention frame changes mandatory governing authority.")
        }
        if (frame.hash != attentionFrameHash(frame)) add("Attention frame hash is invalid.")
    }
    return AttentionAdequacyReport(diagnostics.isEmpty(), diagnostics)
}

fun attentionFrameHash(frame: AttentionFrame): String = stagedPlanHash(
    attentionJson.encodeToString(frame.copy(hash = ""))
)

fun attentionOperationDiagnostic(frame: AttentionFrame, proposedOperations: List<AttentionProposedOperation>): String? {
    val actionable = frame.correlations.asSequence()
        .filter { it.disposition == ATTENTION_DISPOSITION_ACTIONABLE }
        .flatMap { correlation -> correlation.ownerPaths.asSequence().map { it to correlation.allowedActions } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, actions) -> actions.flatten().toSet() }
    val actionablePaths = actionable.keys
        .toSortedSet()
    val proposed = proposedOperations.mapTo(sortedSetOf()) { it.path }
    val drift = proposed - actionablePaths
    if (drift.isNotEmpty()) {
        return "Proposed paths do not reverse-trace to the admitted objective: ${drift.joinToString()}."
    }
    val missing = actionablePaths - proposed
    if (missing.isNotEmpty()) {
        return "Proposed operations omit actionable task-code correlations: ${missing.joinToString()}."
    }
    proposedOperations.forEach { operation ->
        if (operation.action !in actionable.getValue(operation.path)) {
            return "Proposed action ${operation.action} on ${operation.path} does not reverse-trace to the admitted operation."
        }
    }
    return null
}

fun attentionScopeKinds(plan: RepositoryExecutionPlan): Map<Int, AttentionScopeKind> =
    plan.content.scopeCoverage.indices.associateWith { index -> attentionScopeKind(plan.content.scopeCoverage[index].scope) }

internal fun attentionScopeKind(scope: String): AttentionScopeKind {
    val normalized = scope.lowercase()
    return when {
        SECURITY_SCOPE.containsMatchIn(normalized) -> AttentionScopeKind.SECURITY
        OPERATIONAL_SCOPE.containsMatchIn(normalized) -> AttentionScopeKind.OPERATIONAL
        MIGRATION_SCOPE.containsMatchIn(normalized) -> AttentionScopeKind.MIGRATION
        PERSISTENCE_SCOPE.containsMatchIn(normalized) -> AttentionScopeKind.PERSISTENCE
        API_SCOPE.containsMatchIn(normalized) -> AttentionScopeKind.API
        USER_INTERFACE_SCOPE.containsMatchIn(normalized) -> AttentionScopeKind.USER_INTERFACE
        VERIFICATION_SCOPE.containsMatchIn(normalized) -> AttentionScopeKind.VERIFICATION
        DOCUMENTATION_SCOPE.containsMatchIn(normalized) -> AttentionScopeKind.DOCUMENTATION
        else -> AttentionScopeKind.IMPLEMENTATION
    }
}

private fun boundedActions(planAction: String): List<String> = when (planAction) {
    PLAN_OPERATION_CREATE -> listOf("CREATE_FILE")
    PLAN_OPERATION_MODIFY -> listOf("REPLACE_LITERAL", "REWRITE_FILE")
    PLAN_OPERATION_DELETE -> listOf("DELETE_FILE")
    else -> emptyList()
}

private val attentionJson = Json { encodeDefaults = true }
private val SECURITY_SCOPE = Regex("\\b(?:acls?|access control|authenticat(?:e|ed|ion)|authoriz(?:e|ed|ation)|permissions?|privileges?|credentials?|secrets?|tenants?|security)\\b")
private val OPERATIONAL_SCOPE = Regex("\\b(?:deploy|deployment|rollout|rollback|operational|operator|runbook|observability|metric|alert|restart|recovery|configuration|resource|performance)\\b")
private val MIGRATION_SCOPE = Regex("\\b(?:migrate|migration|compatibility|backward compatible|forward compatible)\\b")
private val PERSISTENCE_SCOPE = Regex("\\b(?:persist|persistence|journal|replay|database|storage)\\b")
private val API_SCOPE = Regex("\\b(?:api|endpoint|request|response|protocol)\\b")
private val USER_INTERFACE_SCOPE = Regex("\\b(?:ui|frontend|desktop|screen|view|interaction)\\b")
private val VERIFICATION_SCOPE = Regex("\\b(?:test|verify|verification|acceptance|evidence)\\b")
private val DOCUMENTATION_SCOPE = Regex("\\b(?:document|documentation|readme|runbook)\\b")