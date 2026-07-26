@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.orchard.backend.analysis

import com.orchard.backend.agent.CodingContextFile
import com.orchard.backend.agent.CodingRepositoryContext
import com.orchard.backend.workspace.WorkDefinitionManifest
import com.orchard.backend.workspace.stagedPlanHash
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val EXECUTABLE_WORK_PACKAGE_VERSION = 1

const val WORK_PACKAGE_ACTION_CREATE_FILE = "CREATE_FILE"
const val WORK_PACKAGE_ACTION_DELETE_FILE = "DELETE_FILE"
const val WORK_PACKAGE_ACTION_READ_SOURCE = "READ_SOURCE"
const val WORK_PACKAGE_ACTION_REWRITE_FILE = "REWRITE_FILE"
const val WORK_PACKAGE_ACTION_RUN_CHECK = "RUN_CHECK"

const val WORK_PACKAGE_ESCALATE_DESIGN_CONTRADICTION = "DESIGN_CONTRADICTION"
const val WORK_PACKAGE_ESCALATE_MISSING_AUTHORITY = "MISSING_AUTHORITY"
const val WORK_PACKAGE_ESCALATE_SCOPE_REQUIRED = "SCOPE_REQUIRED"
const val WORK_PACKAGE_ESCALATE_STALE_REVISION = "STALE_REVISION"

@Serializable
data class WorkPackageIntentAuthority(
    val definitionId: Long,
    val definitionRevision: Int,
    val definitionHash: String,
    val requestedOutcome: String,
    val currentBehavior: String,
    val requiredBehavior: String,
    val constraints: List<String>,
    val acceptanceCriteria: List<String>,
)

@Serializable
data class WorkPackageDesignAuthority(
    val executionPlanId: Long,
    val executionPlanRevision: Int,
    val executionPlanHash: String,
    val summary: String,
    val preservedInvariants: List<String>,
    val nonGoals: List<String>,
)

@Serializable
data class WorkPackageOwnershipBoundary(
    val paths: List<String>,
    val likelyImplementationPaths: List<String>,
    val createPaths: List<String>,
    val allowedActions: List<String>,
)

@Serializable
data class WorkPackageSource(
    val path: String,
    val content: String,
    val contentHash: String,
    val matchedDeclarations: List<String> = emptyList(),
)

@Serializable
data class WorkPackageCheck(
    val checkId: String,
    val command: String,
)

@Serializable
data class ExecutableWorkPackage(
    val formatVersion: Int = EXECUTABLE_WORK_PACKAGE_VERSION,
    val packageId: Long,
    val revision: Int,
    val projectId: Int,
    val runId: Long,
    val repositoryRevision: String,
    val intent: WorkPackageIntentAuthority,
    val design: WorkPackageDesignAuthority,
    val ownership: WorkPackageOwnershipBoundary,
    val expectedBehavior: List<String>,
    val unresolvedAuthorityQuestions: List<String>,
    val sources: List<WorkPackageSource>,
    val checks: List<WorkPackageCheck>,
    val escalationConditions: List<String>,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val hash: String = "",
)

@Serializable
data class WorkPackageAdequacyReport(
    val adequate: Boolean,
    val diagnostics: List<String>,
)

fun compileExecutableWorkPackage(
    packageId: Long,
    revision: Int,
    definition: WorkDefinitionManifest,
    plan: RepositoryExecutionPlan,
    repositoryContext: CodingRepositoryContext,
): ExecutableWorkPackage {
    val likelyPaths = plan.content.operations
        .filter { it.action != PLAN_OPERATION_VERIFY }
        .map { it.path }
        .distinct()
        .sorted()
    val ownershipPaths = (plan.content.scopeCoverage.flatMap { it.evidencePaths } +
        plan.content.evidence.map { it.path } + likelyPaths)
        .distinct()
        .sorted()
    val sourceByPath = repositoryContext.files.associateBy(CodingContextFile::path)
    val sources = ownershipPaths.mapNotNull(sourceByPath::get).map { source ->
        WorkPackageSource(source.path, source.content, source.contentHash, source.matchedDeclarations)
    }
    val checks = plan.content.verificationCommands.distinct().mapIndexed { index, command ->
        WorkPackageCheck("check-${index + 1}", command)
    }
    val draft = ExecutableWorkPackage(
        packageId = packageId,
        revision = revision,
        projectId = plan.projectId,
        runId = plan.runId,
        repositoryRevision = plan.baseRevision,
        intent = WorkPackageIntentAuthority(
            definitionId = definition.definitionId,
            definitionRevision = definition.revision,
            definitionHash = definition.hash,
            requestedOutcome = definition.definition.requestedOutcome,
            currentBehavior = definition.definition.currentBehavior,
            requiredBehavior = definition.definition.requiredBehavior,
            constraints = definition.definition.constraints,
            acceptanceCriteria = definition.definition.acceptanceCriteria.map { it.description },
        ),
        design = WorkPackageDesignAuthority(
            executionPlanId = plan.planId,
            executionPlanRevision = plan.revision,
            executionPlanHash = plan.hash,
            summary = plan.content.summary,
            preservedInvariants = plan.content.preservedInvariants,
            nonGoals = (definition.definition.nonGoals + plan.content.nonGoals).distinct(),
        ),
        ownership = WorkPackageOwnershipBoundary(
            paths = ownershipPaths,
            likelyImplementationPaths = likelyPaths,
            createPaths = plan.content.operations.filter { it.action == PLAN_OPERATION_CREATE }.map { it.path }.distinct().sorted(),
            allowedActions = allowedWorkPackageActions(plan),
        ),
        expectedBehavior = (listOf(definition.definition.requiredBehavior) +
            definition.definition.acceptanceCriteria.map { it.description } +
            plan.content.operations.flatMap { it.acceptanceCriteria }).distinct(),
        unresolvedAuthorityQuestions = (definition.definition.unresolvedQuestions + plan.content.unresolvedQuestions).distinct(),
        sources = sources,
        checks = checks,
        escalationConditions = listOf(
            WORK_PACKAGE_ESCALATE_STALE_REVISION,
            WORK_PACKAGE_ESCALATE_SCOPE_REQUIRED,
            WORK_PACKAGE_ESCALATE_MISSING_AUTHORITY,
            WORK_PACKAGE_ESCALATE_DESIGN_CONTRADICTION,
        ),
    )
    return draft.copy(hash = executableWorkPackageHash(draft))
}

fun verifyExecutableWorkPackage(packageAuthority: ExecutableWorkPackage): WorkPackageAdequacyReport {
    val diagnostics = buildList {
        if (packageAuthority.formatVersion != EXECUTABLE_WORK_PACKAGE_VERSION) {
            add("Unsupported executable work-package version ${packageAuthority.formatVersion}.")
        }
        if (packageAuthority.packageId <= 0 || packageAuthority.revision <= 0 || packageAuthority.runId <= 0 || packageAuthority.projectId <= 0) {
            add("Executable work-package identity is invalid.")
        }
        if (!packageAuthority.repositoryRevision.matches(GIT_REVISION)) add("Repository revision is invalid.")
        if (!packageAuthority.intent.definitionHash.matches(SHA256_HASH)) add("Intent authority hash is invalid.")
        if (!packageAuthority.design.executionPlanHash.matches(SHA256_HASH)) add("Design authority hash is invalid.")
        if (packageAuthority.intent.requestedOutcome.isBlank() || packageAuthority.intent.requiredBehavior.isBlank()) {
            add("Intent authority does not define the requested and required behavior.")
        }
        if (packageAuthority.design.summary.isBlank()) add("Admitted design has no implementation summary.")
        if (packageAuthority.expectedBehavior.none(String::isNotBlank)) add("Expected behavior is empty.")
        if (packageAuthority.ownership.paths.isEmpty()) add("Ownership boundary is empty.")
        if (!packageAuthority.ownership.paths.containsAll(packageAuthority.ownership.likelyImplementationPaths)) {
            add("Likely implementation paths exceed the ownership boundary.")
        }
        if (!packageAuthority.ownership.paths.containsAll(packageAuthority.ownership.createPaths)) {
            add("Create paths exceed the ownership boundary.")
        }
        if (packageAuthority.ownership.createPaths.isNotEmpty() && WORK_PACKAGE_ACTION_CREATE_FILE !in packageAuthority.ownership.allowedActions) {
            add("Create paths lack CREATE_FILE authority.")
        }
        if (packageAuthority.ownership.allowedActions.none { it != WORK_PACKAGE_ACTION_READ_SOURCE && it != WORK_PACKAGE_ACTION_RUN_CHECK }) {
            add("Package grants no implementation action.")
        }
        if (packageAuthority.unresolvedAuthorityQuestions.isNotEmpty()) {
            add("Package leaves design authority unresolved: ${packageAuthority.unresolvedAuthorityQuestions.joinToString()}.")
        }
        if (packageAuthority.checks.isEmpty() || packageAuthority.checks.any { it.checkId.isBlank() || it.command.isBlank() }) {
            add("Package has no executable verification strategy.")
        }
        val duplicatePaths = packageAuthority.sources.groupingBy { it.path }.eachCount().filterValues { it > 1 }.keys
        if (duplicatePaths.isNotEmpty()) add("Package contains duplicate source paths: ${duplicatePaths.sorted().joinToString()}.")
        packageAuthority.sources.forEach { source ->
            if (source.path !in packageAuthority.ownership.paths) add("Source ${source.path} is outside the ownership boundary.")
            if (source.content.isBlank()) add("Source ${source.path} has no editable content.")
            if (source.contentHash != stagedPlanHash(source.content)) add("Source ${source.path} does not match its pinned content hash.")
        }
        val sourcePaths = packageAuthority.sources.mapTo(hashSetOf()) { it.path }
        val missingSources = packageAuthority.ownership.paths.filter {
            it !in sourcePaths && it !in packageAuthority.ownership.createPaths
        }
        if (missingSources.isNotEmpty()) add("Ownership boundary lacks pinned source: ${missingSources.joinToString()}.")
        val contradictions = packageAuthority.intent.constraints.map(String::trim).filter(String::isNotEmpty).toSet()
            .intersect(packageAuthority.design.nonGoals.map(String::trim).filter(String::isNotEmpty).toSet())
        if (contradictions.isNotEmpty()) add("Constraints contradict admitted non-goals: ${contradictions.sorted().joinToString()}.")
        if (packageAuthority.escalationConditions.toSet() != REQUIRED_ESCALATION_CONDITIONS) {
            add("Package does not define every required escalation condition.")
        }
        if (packageAuthority.hash != executableWorkPackageHash(packageAuthority)) add("Executable work-package hash is invalid.")
    }
    return WorkPackageAdequacyReport(diagnostics.isEmpty(), diagnostics)
}

fun executableWorkPackageHash(packageAuthority: ExecutableWorkPackage): String = stagedPlanHash(
    workPackageJson.encodeToString(packageAuthority.copy(hash = ""))
)

private fun allowedWorkPackageActions(plan: RepositoryExecutionPlan): List<String> = buildSet {
    add(WORK_PACKAGE_ACTION_READ_SOURCE)
    add(WORK_PACKAGE_ACTION_RUN_CHECK)
    plan.content.operations.forEach { operation ->
        when (operation.action) {
            PLAN_OPERATION_CREATE -> add(WORK_PACKAGE_ACTION_CREATE_FILE)
            PLAN_OPERATION_MODIFY -> add(WORK_PACKAGE_ACTION_REWRITE_FILE)
            PLAN_OPERATION_DELETE -> add(WORK_PACKAGE_ACTION_DELETE_FILE)
        }
    }
}.sorted()

private val REQUIRED_ESCALATION_CONDITIONS = setOf(
    WORK_PACKAGE_ESCALATE_STALE_REVISION,
    WORK_PACKAGE_ESCALATE_SCOPE_REQUIRED,
    WORK_PACKAGE_ESCALATE_MISSING_AUTHORITY,
    WORK_PACKAGE_ESCALATE_DESIGN_CONTRADICTION,
)
private val workPackageJson = Json { encodeDefaults = true }
private val GIT_REVISION = Regex("[0-9a-f]{40}")
private val SHA256_HASH = Regex("[0-9a-f]{64}")