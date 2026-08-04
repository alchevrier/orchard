@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.orchard.backend.analysis

import com.orchard.backend.agent.CodingContextFile
import com.orchard.backend.agent.CodingRepositoryContext
import com.orchard.backend.agent.CandidatePullRequestCorrection
import com.orchard.backend.agent.CandidatePullRequestReviewFinding
import com.orchard.backend.agent.REVIEW_CORRECTION_WORK_PACKAGE_RECOMPILE
import com.orchard.backend.workspace.WorkDefinitionManifest
import com.orchard.backend.workspace.DesignAuthorityReference
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
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val admittedDesign: DesignAuthorityReference? = null,
)

@Serializable
data class WorkPackageOwnershipBoundary(
    val paths: List<String>,
    val likelyImplementationPaths: List<String>,
    val createPaths: List<String>,
    val allowedActions: List<String>,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val requiredImplementationPaths: List<String> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val compliantEvidencePaths: List<String> = emptyList(),
)

@Serializable
data class WorkPackageEvidenceCitation(
    val path: String,
    val symbol: String? = null,
    val observation: String,
    val contentHash: String,
    val compliantReadOnly: Boolean,
)

@Serializable
data class WorkPackageEvidenceAuthority(
    val citations: List<WorkPackageEvidenceCitation> = emptyList(),
)

@Serializable
data class WorkPackageOperation(
    val order: Int,
    val action: String,
    val path: String,
    val symbol: String? = null,
    val instruction: String,
    val acceptanceCriteria: List<String>,
)

@Serializable
data class WorkPackageOperationAuthority(
    val operations: List<WorkPackageOperation> = emptyList(),
)

@Serializable
data class WorkPackageCorrectionAuthority(
    val correctionId: Long,
    val correctionHash: String,
    val reviewHash: String,
    val findings: List<CandidatePullRequestReviewFinding>,
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
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val evidence: WorkPackageEvidenceAuthority = WorkPackageEvidenceAuthority(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val operations: WorkPackageOperationAuthority = WorkPackageOperationAuthority(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val corrections: List<WorkPackageCorrectionAuthority> = emptyList(),
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
    repositoryRevision: String = plan.baseRevision,
    restrictedPaths: Set<String> = emptySet(),
): ExecutableWorkPackage {
    val sourceOperations = plan.content.operations
        .filter { it.action != PLAN_OPERATION_VERIFY }
        .filter { restrictedPaths.isEmpty() || it.path in restrictedPaths }
        .take(MAX_SOURCE_OPERATIONS_PER_WORK_PACKAGE)
    val likelyPaths = sourceOperations.map { it.path }.distinct().sorted()
    val evidencePaths = plan.content.evidence
        .filter { restrictedPaths.isEmpty() || it.path in likelyPaths }
        .map { it.path }
        .toSet()
    val compliantEvidencePaths = plan.content.scopeCoverage
        .flatMap { it.compliantEvidencePaths }
        .filter { it in evidencePaths }
        .distinct()
        .sorted()
    val ownershipPaths = likelyPaths
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
        repositoryRevision = repositoryRevision,
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
            admittedDesign = plan.admittedDesign,
        ),
        ownership = WorkPackageOwnershipBoundary(
            paths = ownershipPaths,
            likelyImplementationPaths = likelyPaths,
            createPaths = sourceOperations.filter { it.action == PLAN_OPERATION_CREATE }.map { it.path }.distinct().sorted(),
            allowedActions = allowedWorkPackageActions(sourceOperations),
            requiredImplementationPaths = likelyPaths,
            compliantEvidencePaths = compliantEvidencePaths,
        ),
        evidence = WorkPackageEvidenceAuthority(
            plan.content.evidence.filter { restrictedPaths.isEmpty() || it.path in likelyPaths }.map { citation ->
                WorkPackageEvidenceCitation(
                    path = citation.path,
                    symbol = citation.symbol,
                    observation = citation.observation,
                    contentHash = citation.contentHash,
                    compliantReadOnly = citation.path in compliantEvidencePaths,
                )
            },
        ),
        operations = WorkPackageOperationAuthority(
            sourceOperations.map { operation ->
                WorkPackageOperation(
                    order = operation.order,
                    action = operation.action,
                    path = operation.path,
                    symbol = operation.symbol,
                    instruction = operation.instruction,
                    acceptanceCriteria = operation.acceptanceCriteria,
                )
            },
        ),
        expectedBehavior = (listOf(definition.definition.requiredBehavior) +
            definition.definition.acceptanceCriteria.map { it.description } +
            sourceOperations.flatMap { it.acceptanceCriteria }).distinct(),
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

fun recompileExecutableWorkPackage(
    packageId: Long,
    revision: Int,
    prior: ExecutableWorkPackage,
    correction: CandidatePullRequestCorrection,
): ExecutableWorkPackage {
    require(correction.correctionTarget == REVIEW_CORRECTION_WORK_PACKAGE_RECOMPILE) {
        "Only work-package recompilation correction authority can produce a successor package."
    }
    require(correction.runId == prior.runId && correction.workPackageId == prior.packageId &&
        correction.workPackageHash == prior.hash
    ) { "Correction authority does not match the pinned executable work package." }
    val attachment = WorkPackageCorrectionAuthority(
        correction.correctionId,
        correction.hash,
        correction.reviewHash,
        correction.findings,
    )
    val compileFailurePaths = compileFailureCorrectionPaths(prior, correction)
    val narrowedOwnership = compileFailurePaths.takeIf(List<String>::isNotEmpty)?.let { paths ->
        prior.ownership.copy(
            paths = paths,
            likelyImplementationPaths = prior.ownership.likelyImplementationPaths.filter { it in paths },
            createPaths = prior.ownership.createPaths.filter { it in paths },
            requiredImplementationPaths = prior.ownership.requiredImplementationPaths.filter { it in paths },
            compliantEvidencePaths = prior.ownership.compliantEvidencePaths.filter { it in paths },
        )
    } ?: prior.ownership
    val draft = prior.copy(
        packageId = packageId,
        revision = revision,
        ownership = narrowedOwnership,
        evidence = if (compileFailurePaths.isEmpty()) prior.evidence else {
            prior.evidence.copy(citations = prior.evidence.citations.filter { it.path in compileFailurePaths })
        },
        operations = if (compileFailurePaths.isEmpty()) prior.operations else {
            prior.operations.copy(operations = prior.operations.operations.filter { it.path in compileFailurePaths })
        },
        sources = if (compileFailurePaths.isEmpty()) prior.sources else {
            prior.sources.filter { it.path in compileFailurePaths }
        },
        corrections = prior.corrections + attachment,
        expectedBehavior = (prior.expectedBehavior + correction.findings.map { it.observation }).distinct(),
        hash = "",
    )
    return draft.copy(hash = executableWorkPackageHash(draft))
}

internal fun compileFailureCorrectionPaths(
    prior: ExecutableWorkPackage,
    correction: CandidatePullRequestCorrection,
): List<String> {
    val diagnostic = correction.findings.joinToString("\n") { "${it.observation}\n${it.criterion}" }
    if (!diagnostic.contains("compil", ignoreCase = true) || !diagnostic.contains("Unresolved reference")) {
        return emptyList()
    }
    return prior.ownership.paths.filter { path ->
        path.endsWith("Test.kt") && diagnostic.contains(path)
    }
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
        if (packageAuthority.corrections.any { correction ->
                correction.correctionId <= 0 || !correction.correctionHash.matches(SHA256_HASH) ||
                    !correction.reviewHash.matches(SHA256_HASH) || correction.findings.isEmpty() ||
                    correction.findings.any { it.correctionTarget != REVIEW_CORRECTION_WORK_PACKAGE_RECOMPILE }
            } || packageAuthority.corrections.map { it.correctionId }.distinct().size != packageAuthority.corrections.size
        ) {
            add("Work-package correction authority is invalid.")
        }
        if (packageAuthority.corrections.any { correction ->
                !packageAuthority.expectedBehavior.containsAll(correction.findings.map { it.observation })
            }
        ) add("Work-package correction behavior is not represented in the package envelope.")
        if (packageAuthority.ownership.paths.isEmpty()) add("Ownership boundary is empty.")
        if (!packageAuthority.ownership.paths.containsAll(packageAuthority.ownership.likelyImplementationPaths)) {
            add("Likely implementation paths exceed the ownership boundary.")
        }
        if (!packageAuthority.ownership.paths.containsAll(packageAuthority.ownership.createPaths)) {
            add("Create paths exceed the ownership boundary.")
        }
        val hasAuthorityGraph = packageAuthority.evidence.citations.isNotEmpty() || packageAuthority.operations.operations.isNotEmpty()
        if (hasAuthorityGraph) {
            if (packageAuthority.evidence.citations.isEmpty() || packageAuthority.operations.operations.isEmpty()) {
                add("Package authority graph is incomplete.")
            }
            val evidencePaths = packageAuthority.evidence.citations.mapTo(hashSetOf()) { it.path }
            if (packageAuthority.evidence.citations.any {
                    it.path.isBlank() || it.observation.isBlank() || !it.contentHash.matches(SHA256_HASH)
                }) {
                add("Package evidence authority is invalid.")
            }
            if (!evidencePaths.containsAll(packageAuthority.ownership.compliantEvidencePaths)) {
                add("Compliant evidence paths lack evidence authority.")
            }
            if (packageAuthority.evidence.citations.any { citation ->
                    citation.compliantReadOnly != (citation.path in packageAuthority.ownership.compliantEvidencePaths)
                }) {
                add("Evidence compliance does not match ownership authority.")
            }
            val operationPaths = packageAuthority.operations.operations.map { it.path }
            if (packageAuthority.operations.operations.map { it.order } != packageAuthority.operations.operations.map { it.order }.sorted() ||
                packageAuthority.operations.operations.any { operation ->
                    operation.action !in setOf(PLAN_OPERATION_CREATE, PLAN_OPERATION_MODIFY, PLAN_OPERATION_DELETE) ||
                        operation.path.isBlank() || operation.instruction.isBlank() || operation.acceptanceCriteria.isEmpty()
                }
            ) {
                add("Package operation authority is invalid.")
            }
            if (operationPaths.distinct().sorted() != packageAuthority.ownership.requiredImplementationPaths.distinct().sorted()) {
                add("Required implementation ownership does not match operation authority.")
            }
            if (!packageAuthority.ownership.paths.containsAll(packageAuthority.ownership.requiredImplementationPaths)) {
                add("Required implementation paths exceed the ownership boundary.")
            }
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

private fun allowedWorkPackageActions(sourceOperations: List<ExecutionPlanOperation>): List<String> = buildSet {
    add(WORK_PACKAGE_ACTION_READ_SOURCE)
    add(WORK_PACKAGE_ACTION_RUN_CHECK)
    sourceOperations.forEach { operation ->
        when (operation.action) {
            PLAN_OPERATION_CREATE -> add(WORK_PACKAGE_ACTION_CREATE_FILE)
            PLAN_OPERATION_MODIFY -> add(WORK_PACKAGE_ACTION_REWRITE_FILE)
            PLAN_OPERATION_DELETE -> add(WORK_PACKAGE_ACTION_DELETE_FILE)
        }
    }
}.sorted()

private const val MAX_SOURCE_OPERATIONS_PER_WORK_PACKAGE = 1

private val REQUIRED_ESCALATION_CONDITIONS = setOf(
    WORK_PACKAGE_ESCALATE_STALE_REVISION,
    WORK_PACKAGE_ESCALATE_SCOPE_REQUIRED,
    WORK_PACKAGE_ESCALATE_MISSING_AUTHORITY,
    WORK_PACKAGE_ESCALATE_DESIGN_CONTRADICTION,
)
private val workPackageJson = Json { encodeDefaults = true }
private val GIT_REVISION = Regex("[0-9a-f]{40}")
private val SHA256_HASH = Regex("[0-9a-f]{64}")