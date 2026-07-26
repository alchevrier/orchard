package com.orchard.backend.agent

import com.orchard.backend.analysis.ExecutableWorkPackage
import com.orchard.backend.analysis.WORK_PACKAGE_ACTION_CREATE_FILE
import com.orchard.backend.analysis.WORK_PACKAGE_ACTION_DELETE_FILE
import com.orchard.backend.analysis.WORK_PACKAGE_ACTION_REWRITE_FILE
import com.orchard.backend.analysis.WORK_PACKAGE_ACTION_RUN_CHECK
import com.orchard.backend.analysis.verifyExecutableWorkPackage
import kotlinx.serialization.Serializable

const val BOUNDED_TOOL_CREATE_FILE = "CREATE_FILE"
const val BOUNDED_TOOL_DELETE_FILE = "DELETE_FILE"
const val BOUNDED_TOOL_REPLACE_LITERAL = "REPLACE_LITERAL"
const val BOUNDED_TOOL_REWRITE_FILE = "REWRITE_FILE"

@Serializable
data class BoundedCodingToolOperation(
    val action: String,
    val path: String,
    val content: String? = null,
    val expectedLiteral: String? = null,
    val replacement: String? = null,
    val expectedCount: Int? = null,
)

@Serializable
data class BoundedCodingToolBatch(
    val summary: String,
    val expectedRevision: String,
    val operations: List<BoundedCodingToolOperation>,
)

fun CodingWorkspaceGateway.readAuthorizedSource(
    workspacePath: String,
    packageAuthority: ExecutableWorkPackage,
    expectedRevision: String,
    path: String,
): CodingContextFile {
    requireAdequatePackage(packageAuthority)
    require(path in packageAuthority.ownership.paths) { "Source path $path is outside the work-package ownership boundary" }
    require(currentRevision(workspacePath) == expectedRevision) { "Coding workspace revision is stale" }
    val context = collectIntelligenceContext(workspacePath, expectedRevision, listOf(path))
    require(context.omittedFileCount == 0 && context.files.size == 1) { "Authorized source $path is unavailable" }
    return context.files.single()
}

fun CodingWorkspaceGateway.applyBoundedToolBatch(
    workspacePath: String,
    packageAuthority: ExecutableWorkPackage,
    batch: BoundedCodingToolBatch,
    executionId: Long,
): CodingCandidate {
    requireAdequatePackage(packageAuthority)
    require(currentRevision(workspacePath) == batch.expectedRevision) { "Coding workspace revision is stale" }
    require(batch.operations.isNotEmpty()) { "Bounded coding tool batch has no operations" }
    require(batch.operations.map { it.path }.distinct().size == batch.operations.size) {
        "Bounded coding tool batch contains duplicate paths"
    }
    val proposalOperations = batch.operations.map { operation ->
        require(operation.path in packageAuthority.ownership.paths) {
            "Tool path ${operation.path} is outside the work-package ownership boundary"
        }
        when (operation.action) {
            BOUNDED_TOOL_CREATE_FILE -> {
                require(WORK_PACKAGE_ACTION_CREATE_FILE in packageAuthority.ownership.allowedActions &&
                    operation.path in packageAuthority.ownership.createPaths) {
                    "CREATE_FILE ${operation.path} is not authorized by the work package"
                }
                require(operation.content != null && operation.expectedLiteral == null && operation.replacement == null &&
                    operation.expectedCount == null) { "CREATE_FILE ${operation.path} has an invalid payload" }
                val existing = collectIntelligenceContext(
                    workspacePath,
                    batch.expectedRevision,
                    listOf(operation.path),
                )
                require(existing.files.isEmpty() && existing.omittedFileCount == 1) {
                    "CREATE_FILE ${operation.path} cannot overwrite an existing path"
                }
                CodingFileOperation(CODING_FILE_WRITE, operation.path, content = operation.content)
            }
            BOUNDED_TOOL_REWRITE_FILE -> {
                require(WORK_PACKAGE_ACTION_REWRITE_FILE in packageAuthority.ownership.allowedActions &&
                    operation.path !in packageAuthority.ownership.createPaths) {
                    "REWRITE_FILE ${operation.path} is not authorized by the work package"
                }
                require(operation.content != null && operation.expectedLiteral == null && operation.replacement == null &&
                    operation.expectedCount == null) { "REWRITE_FILE ${operation.path} has an invalid payload" }
                readAuthorizedSource(workspacePath, packageAuthority, batch.expectedRevision, operation.path)
                CodingFileOperation(CODING_FILE_WRITE, operation.path, content = operation.content)
            }
            BOUNDED_TOOL_REPLACE_LITERAL -> {
                require(WORK_PACKAGE_ACTION_REWRITE_FILE in packageAuthority.ownership.allowedActions &&
                    operation.path !in packageAuthority.ownership.createPaths) {
                    "REPLACE_LITERAL ${operation.path} is not authorized by the work package"
                }
                val literal = requireNotNull(operation.expectedLiteral) {
                    "REPLACE_LITERAL ${operation.path} requires expectedLiteral"
                }
                val replacement = requireNotNull(operation.replacement) {
                    "REPLACE_LITERAL ${operation.path} requires replacement"
                }
                val expectedCount = requireNotNull(operation.expectedCount) {
                    "REPLACE_LITERAL ${operation.path} requires expectedCount"
                }
                require(operation.content == null && literal.isNotEmpty() && expectedCount > 0 && literal != replacement) {
                    "REPLACE_LITERAL ${operation.path} has an invalid payload"
                }
                val source = readAuthorizedSource(workspacePath, packageAuthority, batch.expectedRevision, operation.path).content
                val actualCount = exactLiteralCount(source, literal)
                require(actualCount == expectedCount) {
                    "REPLACE_LITERAL ${operation.path} found $actualCount occurrences; expected $expectedCount"
                }
                CodingFileOperation(CODING_FILE_WRITE, operation.path, content = source.replace(literal, replacement))
            }
            BOUNDED_TOOL_DELETE_FILE -> {
                require(WORK_PACKAGE_ACTION_DELETE_FILE in packageAuthority.ownership.allowedActions &&
                    operation.path !in packageAuthority.ownership.createPaths) {
                    "DELETE_FILE ${operation.path} is not authorized by the work package"
                }
                require(operation.content == null && operation.expectedLiteral == null && operation.replacement == null &&
                    operation.expectedCount == null) { "DELETE_FILE ${operation.path} has an invalid payload" }
                readAuthorizedSource(workspacePath, packageAuthority, batch.expectedRevision, operation.path)
                CodingFileOperation(CODING_FILE_DELETE, operation.path)
            }
            else -> throw IllegalArgumentException("Unsupported bounded coding tool action ${operation.action}")
        }
    }
    require(currentRevision(workspacePath) == batch.expectedRevision) { "Coding workspace changed before tool application" }
    return applyAndCommit(workspacePath, CodingPatchProposal(batch.summary, proposalOperations), executionId)
}

fun CodingWorkspaceGateway.runNamedCheck(
    workspacePath: String,
    packageAuthority: ExecutableWorkPackage,
    expectedRevision: String,
    checkId: String,
): VerificationObservation {
    requireAdequatePackage(packageAuthority)
    require(WORK_PACKAGE_ACTION_RUN_CHECK in packageAuthority.ownership.allowedActions) {
        "RUN_CHECK is not authorized by the work package"
    }
    require(currentRevision(workspacePath) == expectedRevision) { "Coding workspace revision is stale" }
    val check = packageAuthority.checks.singleOrNull { it.checkId == checkId }
        ?: throw IllegalArgumentException("Unknown work-package check $checkId")
    return executeVerification(workspacePath, parseVerificationCommand(check.command), check.command)
}

private fun requireAdequatePackage(packageAuthority: ExecutableWorkPackage) {
    val report = verifyExecutableWorkPackage(packageAuthority)
    require(report.adequate) { "Executable work package is inadequate: ${report.diagnostics.joinToString(" ")}" }
}

private fun exactLiteralCount(source: String, literal: String): Int {
    var count = 0
    var offset = 0
    while (offset <= source.length - literal.length) {
        val index = source.indexOf(literal, offset)
        if (index < 0) break
        count++
        offset = index + literal.length
    }
    return count
}