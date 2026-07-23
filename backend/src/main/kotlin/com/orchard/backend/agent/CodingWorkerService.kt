package com.orchard.backend.agent

import com.orchard.backend.analysis.DISPOSITION_COMPLETE
import com.orchard.backend.analysis.PLAN_OPERATION_CREATE
import com.orchard.backend.analysis.PLAN_OPERATION_DELETE
import com.orchard.backend.analysis.PLAN_OPERATION_MODIFY
import com.orchard.backend.analysis.RepositoryAnalysisService
import com.orchard.backend.analysis.RepositoryExecutionPlan
import com.orchard.backend.company.CompanyControlService
import com.orchard.backend.company.CompanyMutationStatus
import com.orchard.backend.company.RISK_HIGH
import com.orchard.backend.company.ROLE_IMPLEMENTER
import com.orchard.backend.resource.MachineResourceController
import com.orchard.backend.resource.ModelWorkPriority
import com.orchard.backend.resource.ResourceAdmissionDecision
import com.orchard.backend.vector.DefaultModelExecutionProfiles
import com.orchard.backend.vector.ModelBindingProfile
import com.orchard.backend.vector.ModelExecutionProfile
import com.orchard.backend.vector.ModelGeneration
import com.orchard.backend.vector.ModelProfileOverride
import com.orchard.backend.vector.ModelProfileResolver
import com.orchard.backend.vector.ModelProfileSettingsStore
import com.orchard.backend.vector.ModelProvider
import com.orchard.backend.vector.TransientModelProfileSettingsStore
import com.orchard.backend.vector.effectiveModelExecutionProfile
import com.orchard.backend.vector.estimateModelTokens
import com.orchard.backend.vector.modelBindingFingerprint
import com.orchard.backend.workspace.CRITERION_HUMAN
import com.orchard.backend.workspace.EvidenceRequirement
import com.orchard.backend.workspace.EvidenceSubmission
import com.orchard.backend.workspace.MESSAGE_READY
import com.orchard.backend.workspace.ModelExecutionObservationDraft
import com.orchard.backend.workspace.RUN_STATE_CONTEXT_READY
import com.orchard.backend.workspace.RUN_STATE_EVIDENCE_BLOCKED
import com.orchard.backend.workspace.RUN_STATE_EVIDENCE_PENDING
import com.orchard.backend.workspace.WorkflowMutationStatus
import com.orchard.backend.workspace.WorkflowRunView
import com.orchard.backend.workspace.WorkspaceStore
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class CodingWorkerTickStatus {
    IDLE,
    BUSY,
    INTERRUPTED_RECOVERED,
    RESOURCE_BLOCKED,
    MODEL_FAILED,
    INVALID_PROPOSAL,
    APPLICATION_FAILED,
    VERIFICATION_FAILED,
    CANDIDATE_COMPLETED,
    ANALYSIS_REQUIRED,
    PLAN_STALE,
    PLAN_BLOCKED,
    STORAGE_UNAVAILABLE,
}

@Serializable
data class CodingWorkerTickResult(
    val status: CodingWorkerTickStatus,
    val execution: CodingWorkerExecutionView? = null,
)

@Serializable
private data class CodingWorkerModelEnvelope(
    val executionProfile: ModelExecutionProfile,
    val workflowStepId: String,
    val allowedActions: List<String>,
    val forbiddenActions: List<String>,
    val requiredOutputSchema: String,
    val run: WorkflowRunView,
    val executionPlan: RepositoryExecutionPlan? = null,
    val repositoryContext: CodingRepositoryContext,
)

class CodingWorkerService(
    private val workspace: WorkspaceStore,
    private val modelProviders: List<ModelProvider>,
    private val workerStore: CodingWorkerStore = TransientCodingWorkerStore(),
    private val workspaceGateway: CodingWorkspaceGateway = LocalCodingWorkspaceGateway(),
    private val resourceController: MachineResourceController = MachineResourceController.unrestricted(),
    private val json: Json = Json { encodeDefaults = true },
    private val systemPrompt: String = loadPrompt(),
    private val retryBudget: Int = DEFAULT_RETRY_BUDGET,
    private val companyControl: CompanyControlService? = null,
    private val repositoryAnalysis: RepositoryAnalysisService? = null,
    private val profileSettingsStore: ModelProfileSettingsStore = TransientModelProfileSettingsStore(),
) {
    private val runMutexes = ConcurrentHashMap<Long, Mutex>()
    private val strictOutputJson = Json { encodeDefaults = true }

    init {
        require(retryBudget in 1..MAX_RETRY_BUDGET) { "Coding worker retry budget is invalid" }
        workerStore.loadEvents()
    }

    fun executions(): List<CodingWorkerExecutionView> = codingWorkerExecutions(workerStore.loadEvents())

    suspend fun tick(): CodingWorkerTickResult {
        val runId = eligibleRunIds().firstOrNull() ?: return CodingWorkerTickResult(CodingWorkerTickStatus.IDLE)
        return tick(runId)
    }

    fun eligibleRunIds(): List<Long> = candidateRuns(codingWorkerExecutions(workerStore.loadEvents())).map { it.runId }

    suspend fun tick(runId: Long): CodingWorkerTickResult {
        val mutex = runMutexes.computeIfAbsent(runId) { Mutex() }
        if (!mutex.tryLock()) return CodingWorkerTickResult(CodingWorkerTickStatus.BUSY)
        return try {
            executeTick(runId)
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun executeTick(runId: Long): CodingWorkerTickResult {
        val events = workerStore.loadEvents()
        val executions = codingWorkerExecutions(events)
        executions.singleOrNull { it.claim.runId == runId && it.result == null }?.let { interrupted ->
            val result = terminalResult(
                interrupted.claim.executionId,
                CODING_EXECUTION_INTERRUPTED,
                diagnostic = "The process stopped before this execution recorded a terminal result.",
            )
            return appendResult(events, interrupted.claim, result, CodingWorkerTickStatus.INTERRUPTED_RECOVERED)
        }
        val run = candidateRuns(executions).singleOrNull { it.runId == runId }
            ?: return CodingWorkerTickResult(CodingWorkerTickStatus.IDLE)
        val executionPlan = repositoryAnalysis?.currentPlan(run.runId)
        if (repositoryAnalysis != null && executionPlan == null) {
            return CodingWorkerTickResult(CodingWorkerTickStatus.ANALYSIS_REQUIRED)
        }
        val workspacePath = requireNotNull(run.context.workspaceReservation).path
        if (executionPlan != null && workspaceGateway.currentRevision(workspacePath) != executionPlan.baseRevision) {
            return CodingWorkerTickResult(CodingWorkerTickStatus.PLAN_STALE)
        }
        if (executionPlan?.content?.disposition == DISPOSITION_COMPLETE) {
            return CodingWorkerTickResult(CodingWorkerTickStatus.PLAN_BLOCKED)
        }
        val defaultProfile = DefaultModelExecutionProfiles.boundedCodingPatch
        val profileOverride = runCatching { profileSettingsStore.load() }.getOrElse {
            return CodingWorkerTickResult(CodingWorkerTickStatus.STORAGE_UNAVAILABLE)
        }.singleOrNull { it.profileId == defaultProfile.id }
        val profile = effectiveModelExecutionProfile(defaultProfile, profileOverride)
        val assignment = companyControl?.let { company ->
            if (company.compileRules(run.context.projectId).status != CompanyMutationStatus.RECORDED) {
                return CodingWorkerTickResult(CodingWorkerTickStatus.STORAGE_UNAVAILABLE)
            }
            val priorFailures = executions.count { it.claim.runId == run.runId && it.result?.status == CODING_EXECUTION_FAILED }
            val current = company.assignment(run.runId, ROLE_IMPLEMENTER)
            if (current != null && priorFailures > 0 && current.assignmentId == executions.lastOrNull { it.claim.runId == run.runId }?.claim?.assignmentId) {
                company.escalate(run.runId, ROLE_IMPLEMENTER, "The previous coding attempt failed verification or candidate application.")
            }
            if (company.assign(run.runId, ROLE_IMPLEMENTER, RISK_HIGH).status != CompanyMutationStatus.RECORDED) {
                return CodingWorkerTickResult(CodingWorkerTickStatus.MODEL_FAILED)
            }
            company.assignment(run.runId, ROLE_IMPLEMENTER)
                ?: return CodingWorkerTickResult(CodingWorkerTickStatus.MODEL_FAILED)
        }
        val modelProvider = assignment?.let { companyControl?.provider(it) }
            ?: resolveProvider(profile, profileOverride)
            ?: return CodingWorkerTickResult(CodingWorkerTickStatus.MODEL_FAILED)
        val binding = modelProvider.bindingProfile()
        val toolchainResolution = runCatching { workspaceGateway.resolveToolchainPolicy(workspacePath) }
        val toolchainPolicy = toolchainResolution.getOrNull()
        val claim = try {
            requireNotNull(workerStore.appendNext { eventId, preceding ->
                val currentExecutions = codingWorkerExecutions(preceding)
                val claim = newClaim(eventId, currentExecutions, run, binding, toolchainPolicy, assignment, executionPlan)
                CodingWorkerEvent(eventId = eventId, claim = claim)
            }.claim)
        } catch (_: Exception) {
            return CodingWorkerTickResult(CodingWorkerTickStatus.STORAGE_UNAVAILABLE)
        }
        toolchainResolution.exceptionOrNull()?.let { error ->
            return finish(
                claim,
                CODING_EXECUTION_DEFERRED,
                CodingWorkerTickStatus.APPLICATION_FAILED,
                "Toolchain policy catalog is temporarily unreadable: ${error.message.orEmpty()}",
                retryAfter = Instant.now().plus(TRANSIENT_RETRY_DELAY).toString(),
            )
        }
        if (toolchainPolicy == null) return finish(
            claim,
            CODING_EXECUTION_BLOCKED,
            CodingWorkerTickStatus.APPLICATION_FAILED,
            "No valid toolchain policy matches the reserved repository.",
        )

        val collectedContext = runCatching {
            workspaceGateway.collectContext(workspacePath, run.context.title + "\n" + run.context.content)
        }.getOrElse { error ->
            return finish(claim, CODING_EXECUTION_BLOCKED, CodingWorkerTickStatus.APPLICATION_FAILED, error.message)
        }
        val repositoryContext = executionPlan?.let { plan ->
            val targetPaths = plan.content.operations.mapTo(hashSetOf()) { it.path }
            collectedContext.copy(files = collectedContext.files.filter { it.path in targetPaths })
        } ?: collectedContext
        val envelope = CodingWorkerModelEnvelope(
            executionProfile = profile,
            workflowStepId = CODING_WORKFLOW_STEP_ID,
            allowedActions = listOf(CODING_FILE_WRITE, CODING_FILE_DELETE),
            forbiddenActions = listOf("EXECUTE_COMMAND", "APPROVE_CRITERION", "COMPLETE_WORKFLOW", "PUSH", "MERGE"),
            requiredOutputSchema = CODING_PROPOSAL_SCHEMA,
            run = run,
            executionPlan = executionPlan,
            repositoryContext = repositoryContext,
        )
        val envelopeJson = json.encodeToString(envelope)
        val prompt = "$systemPrompt\n\nAuthoritative coding execution envelope:\n$envelopeJson"
        if (estimateModelTokens(prompt) > profile.inputBudgetTokens) {
            return finish(claim, CODING_EXECUTION_BLOCKED, CodingWorkerTickStatus.INVALID_PROPOSAL, "Coding context exceeds the model input budget.")
        }
        val admission = resourceController.acquire(modelProvider.resourceDemand(profile), ModelWorkPriority.DELIVERY)
        val lease = admission.lease
        if (lease == null) {
            val execution = recordModelExecution(profile, binding, run, envelopeJson, prompt, null, 0, false, admission.evidence)
            return finish(
                claim,
                CODING_EXECUTION_DEFERRED,
                CodingWorkerTickStatus.RESOURCE_BLOCKED,
                admission.evidence.reason,
                modelExecutionId = execution?.executionId,
                retryAfter = Instant.now().plus(TRANSIENT_RETRY_DELAY).toString(),
            )
        }
        val startedAt = System.nanoTime()
        val generation = try {
            lease.use {
                modelProvider.executeCodingPatch(
                    prompt,
                    profile.outputBudgetTokens,
                    profile.inputBudgetTokens + profile.outputBudgetTokens,
                )
            }
        } catch (exception: CancellationException) {
            val execution = recordModelExecution(
                profile, binding, run, envelopeJson, prompt, null, elapsedMillis(startedAt), false, admission.evidence
            )
            finish(
                claim,
                CODING_EXECUTION_INTERRUPTED,
                CodingWorkerTickStatus.MODEL_FAILED,
                "Coding model execution was cancelled.",
                modelExecutionId = execution?.executionId,
            )
            throw exception
        } catch (error: Exception) {
            val execution = recordModelExecution(
                profile, binding, run, envelopeJson, prompt, null, elapsedMillis(startedAt), false, admission.evidence
            )
            return finish(
                claim,
                CODING_EXECUTION_FAILED,
                CodingWorkerTickStatus.MODEL_FAILED,
                error.message,
                modelExecutionId = execution?.executionId,
            )
        }
        val outputWithinBudget = generation.promptTokens <= profile.inputBudgetTokens &&
            generation.completionTokens <= profile.outputBudgetTokens &&
            estimateModelTokens(generation.text) <= profile.outputBudgetTokens
        val proposal = if (outputWithinBudget) {
            runCatching { strictOutputJson.decodeFromString<CodingPatchProposal>(generation.text) }.getOrNull()
        } else null
        val modelExecution = recordModelExecution(
            profile,
            binding,
            run,
            envelopeJson,
            prompt,
            generation,
            elapsedMillis(startedAt),
            proposal != null,
            admission.evidence,
        ) ?: return finish(
            claim,
            CODING_EXECUTION_FAILED,
            CodingWorkerTickStatus.STORAGE_UNAVAILABLE,
            "Model execution provenance could not be saved.",
        )
        if (proposal == null) return finish(
            claim,
            CODING_EXECUTION_FAILED,
            CodingWorkerTickStatus.INVALID_PROPOSAL,
            "The coding model returned invalid or oversized proposal JSON.",
            modelExecutionId = modelExecution.executionId,
        )
        val proposalHash = sha256(strictOutputJson.encodeToString(proposal))
        if (executionPlan != null && !proposalAuthorized(proposal, executionPlan)) return finish(
            claim,
            CODING_EXECUTION_FAILED,
            CodingWorkerTickStatus.PLAN_BLOCKED,
            "The coding proposal exceeds the accepted execution-plan path or action scope.",
            modelExecution.executionId,
            proposalHash,
        )
        if (!runStillActionable(run)) return finish(
            claim,
            CODING_EXECUTION_BLOCKED,
            CodingWorkerTickStatus.APPLICATION_FAILED,
            "The workflow run changed or closed before candidate mutation.",
            modelExecution.executionId,
            proposalHash,
        )
        val candidate = runCatching {
            workspaceGateway.applyAndCommit(
                requireNotNull(run.context.workspaceReservation).path,
                proposal,
                claim.executionId,
            )
        }.getOrElse { error ->
            return finish(
                claim,
                CODING_EXECUTION_FAILED,
                CodingWorkerTickStatus.APPLICATION_FAILED,
                error.message,
                modelExecution.executionId,
                proposalHash,
            )
        }
        val evidenceResult = submitEvidence(run, candidate, toolchainPolicy)
        return if (evidenceResult == null) {
            finish(
                claim,
                CODING_EXECUTION_COMPLETED,
                CodingWorkerTickStatus.CANDIDATE_COMPLETED,
                "Candidate revision was committed and all executable evidence was submitted.",
                modelExecution.executionId,
                proposalHash,
                candidate,
            )
        } else {
            finish(
                claim,
                CODING_EXECUTION_FAILED,
                CodingWorkerTickStatus.VERIFICATION_FAILED,
                evidenceResult,
                modelExecution.executionId,
                proposalHash,
                candidate,
            )
        }
    }

    private fun candidateRuns(executions: List<CodingWorkerExecutionView>): List<WorkflowRunView> {
        val attempts = executions.filter { it.result?.status in REPAIR_ATTEMPT_STATUSES }
            .groupingBy { it.claim.runId }
            .eachCount()
        val blockedRuns = executions.groupBy { it.claim.runId }.mapNotNull { (runId, runExecutions) ->
            runExecutions.maxByOrNull { it.claim.executionId }?.result
                ?.takeIf { it.status == CODING_EXECUTION_BLOCKED }
                ?.let { runId }
        }.toSet()
        val deferredRuns = executions.groupBy { it.claim.runId }.mapNotNull { (runId, runExecutions) ->
            runExecutions.maxByOrNull { it.claim.executionId }?.result
                ?.takeIf { it.status == CODING_EXECUTION_DEFERRED && Instant.parse(requireNotNull(it.retryAfter)).isAfter(Instant.now()) }
                ?.let { runId }
        }.toSet()
        val activeRuns = executions.filter { it.result == null }.mapTo(hashSetOf()) { it.claim.runId }
        return workspace.snapshot(MESSAGE_READY).workflowRuns.asSequence()
            .filter { it.state in setOf(RUN_STATE_CONTEXT_READY, RUN_STATE_EVIDENCE_PENDING, RUN_STATE_EVIDENCE_BLOCKED) }
            .filter { run ->
                run.context.circuitDispatchId != null &&
                    run.context.workspaceReservation?.mode in setOf("ISOLATED", "INTEGRATION")
            }
            .filter { it.runId !in blockedRuns }
            .filter { it.runId !in deferredRuns }
            .filter { it.runId !in activeRuns }
            .filter { attempts.getOrDefault(it.runId, 0) < retryBudget }
            .sortedBy { it.runId }
            .toList()
    }

    private fun newClaim(
        executionId: Long,
        executions: List<CodingWorkerExecutionView>,
        run: WorkflowRunView,
        binding: ModelBindingProfile,
        toolchainPolicy: ResolvedToolchainPolicy?,
        assignment: com.orchard.backend.company.StaffAssignment?,
        executionPlan: RepositoryExecutionPlan?,
    ): CodingWorkerClaim {
        val draft = CodingWorkerClaim(
            executionId = executionId,
            runId = run.runId,
            attempt = executions.count { it.claim.runId == run.runId } + 1,
            contextHash = run.context.hash,
            workspacePath = requireNotNull(run.context.workspaceReservation).path,
            bindingFingerprint = modelBindingFingerprint(binding),
            assignmentId = assignment?.assignmentId,
            staffRole = assignment?.role,
            riskClass = assignment?.risk,
            executionPlanId = executionPlan?.planId,
            executionPlanHash = executionPlan?.hash,
            toolchainPackId = toolchainPolicy?.packId,
            toolchainPackVersion = toolchainPolicy?.packVersion,
            toolchainProfileId = toolchainPolicy?.profileId,
            toolchainPolicyHash = toolchainPolicy?.policyHash,
            hash = "",
        )
        return draft.copy(hash = codingWorkerClaimHash(draft))
    }

    private fun proposalAuthorized(proposal: CodingPatchProposal, plan: RepositoryExecutionPlan): Boolean {
        val authority = plan.content.operations.filter { it.action != "VERIFY" }.associate { it.path to it.action }
        return proposal.operations.isNotEmpty() && proposal.operations.all { operation ->
            when (operation.action) {
                CODING_FILE_WRITE -> authority[operation.path] in setOf(PLAN_OPERATION_CREATE, PLAN_OPERATION_MODIFY)
                CODING_FILE_DELETE -> authority[operation.path] == PLAN_OPERATION_DELETE
                else -> false
            }
        }
    }

    private fun resolveProvider(profile: ModelExecutionProfile, override: ModelProfileOverride?): ModelProvider? {
        val eligible = override?.preferredBindingId?.let { preferred ->
            modelProviders.filter { it.bindingProfile().bindingId == preferred }
        } ?: modelProviders
        return runCatching { ModelProfileResolver.resolve(profile, eligible) }.getOrNull()
    }

    private fun submitEvidence(
        run: WorkflowRunView,
        candidate: CodingCandidate,
        toolchainPolicy: ResolvedToolchainPolicy,
    ): String? {
        val requirements = run.workflow.evidenceContract.requirements
        for (requirement in requirements) {
            if (requirement.gate == CRITERION_HUMAN) continue
            if (run.evidence.any { it.kind == requirement.kind && it.revision == candidate.revision && it.passed }) continue
            val observation = if (requirement.kind == "SOURCE_DIFF") {
                VerificationObservation("", 0, sha256(candidate.changedPaths.joinToString("\n")), "Candidate source diff was committed.")
            } else {
                val command = runCatching { verificationCommand(requirement, toolchainPolicy) }
                    .getOrElse {
                        return "Evidence ${requirement.kind} has an invalid admitted verification command: ${it.message.orEmpty()}"
                    }
                    ?: return "Evidence ${requirement.kind} has no admitted or repository verification command."
                runCatching {
                    workspaceGateway.executeVerification(
                        requireNotNull(run.context.workspaceReservation).path,
                        command.command,
                        command.evidenceCommand,
                    )
                }.getOrElse { return "Verification ${requirement.kind} could not run: ${it.message.orEmpty()}" }
            }
            val result = workspace.submitEvidence(
                run.runId,
                EvidenceSubmission(
                    kind = requirement.kind,
                    revision = candidate.revision,
                    command = observation.command,
                    exitCode = observation.exitCode,
                    outputHash = observation.outputHash,
                    summary = observation.summary,
                    producer = CODING_EVIDENCE_PRODUCER,
                ),
            )
            if (result.status != WorkflowMutationStatus.RECORDED) {
                return "Evidence ${requirement.kind} was rejected with ${result.status}."
            }
            val recorded = result.snapshot.workflowRuns.single { it.runId == run.runId }.evidence
                .last { it.kind == requirement.kind && it.revision == candidate.revision }
            if (!recorded.passed) return "Verification ${requirement.kind} failed: ${recorded.summary}"
        }
        return null
    }

    private fun runStillActionable(expected: WorkflowRunView): Boolean =
        workspace.snapshot(MESSAGE_READY).workflowRuns.singleOrNull { it.runId == expected.runId }?.let { current ->
            current.state in setOf(RUN_STATE_CONTEXT_READY, RUN_STATE_EVIDENCE_PENDING, RUN_STATE_EVIDENCE_BLOCKED) &&
                current.context.hash == expected.context.hash &&
                current.context.circuitDispatchId == expected.context.circuitDispatchId &&
                current.context.workspaceReservation == expected.context.workspaceReservation
        } == true

    private fun verificationCommand(
        requirement: EvidenceRequirement,
        toolchainPolicy: ResolvedToolchainPolicy,
    ): VerificationInvocation? = requirement.verification?.takeIf(String::isNotBlank)?.let { admitted ->
        VerificationInvocation(workspaceGateway.parseVerificationCommand(admitted), admitted)
    } ?: toolchainPolicy.commands[
        when (requirement.kind) {
            "REGRESSION_TEST", "ACCEPTANCE" -> "TEST"
            else -> requirement.kind
        }
    ]?.let { command -> VerificationInvocation(command, command.canonical()) }

    private data class VerificationInvocation(
        val command: VerificationCommand,
        val evidenceCommand: String,
    )

    private fun recordModelExecution(
        profile: ModelExecutionProfile,
        binding: ModelBindingProfile,
        run: WorkflowRunView,
        envelopeJson: String,
        prompt: String,
        generation: ModelGeneration?,
        latencyMillis: Long,
        schemaValid: Boolean,
        admission: com.orchard.backend.resource.ResourceAdmissionEvidence,
    ) = workspace.recordModelExecution(
        ModelExecutionObservationDraft(
            profile = profile,
            binding = binding,
            workflowStepId = CODING_WORKFLOW_STEP_ID,
            workItemId = run.context.workItemId,
            envelopeHash = sha256(envelopeJson),
            promptHash = sha256(prompt),
            outputHash = generation?.text?.let(::sha256),
            inputTokens = generation?.promptTokens ?: estimateModelTokens(prompt),
            outputTokens = generation?.completionTokens ?: 0,
            latencyMillis = latencyMillis,
            schemaValid = schemaValid,
            resourceAdmission = admission,
        )
    )

    private fun finish(
        claim: CodingWorkerClaim,
        status: String,
        tickStatus: CodingWorkerTickStatus,
        diagnostic: String?,
        modelExecutionId: Long? = null,
        proposalHash: String? = null,
        candidate: CodingCandidate? = null,
        retryAfter: String? = null,
    ): CodingWorkerTickResult {
        val result = terminalResult(
            claim.executionId,
            status,
            diagnostic = diagnostic?.take(MAX_DIAGNOSTIC_LENGTH).orEmpty().ifBlank { "Coding execution failed without a diagnostic." },
            modelExecutionId = modelExecutionId,
            proposalHash = proposalHash,
            candidate = candidate,
            retryAfter = retryAfter,
        )
        return appendResult(workerStore.loadEvents(), claim, result, tickStatus)
    }

    private fun terminalResult(
        executionId: Long,
        status: String,
        diagnostic: String,
        modelExecutionId: Long? = null,
        proposalHash: String? = null,
        candidate: CodingCandidate? = null,
        retryAfter: String? = null,
    ): CodingWorkerResult {
        val draft = CodingWorkerResult(
            executionId = executionId,
            status = status,
            modelExecutionId = modelExecutionId,
            proposalHash = proposalHash,
            changedPaths = candidate?.changedPaths.orEmpty(),
            revision = candidate?.revision,
            diagnostic = diagnostic,
            retryAfter = retryAfter,
            hash = "",
        )
        return draft.copy(hash = codingWorkerResultHash(draft))
    }

    private fun appendResult(
        events: List<CodingWorkerEvent>,
        claim: CodingWorkerClaim,
        result: CodingWorkerResult,
        status: CodingWorkerTickStatus,
    ): CodingWorkerTickResult = try {
        workerStore.appendNext { eventId, _ -> CodingWorkerEvent(eventId = eventId, result = result) }
        CodingWorkerTickResult(status, CodingWorkerExecutionView(claim, result))
    } catch (_: Exception) {
        CodingWorkerTickResult(CodingWorkerTickStatus.STORAGE_UNAVAILABLE, CodingWorkerExecutionView(claim))
    }

    private fun elapsedMillis(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

    private companion object {
        const val CODING_WORKFLOW_STEP_ID = "DELIVER_CHANGE:CODING_PATCH"
        const val CODING_PROPOSAL_SCHEMA = "coding-patch-proposal-v1"
        const val CODING_EVIDENCE_PRODUCER = "orchard-coding-worker-v1"
        const val DEFAULT_RETRY_BUDGET = 3
        const val MAX_RETRY_BUDGET = 10
        const val MAX_DIAGNOSTIC_LENGTH = 4_096
        val TRANSIENT_RETRY_DELAY: Duration = Duration.ofSeconds(30)
        val REPAIR_ATTEMPT_STATUSES = setOf(CODING_EXECUTION_COMPLETED, CODING_EXECUTION_FAILED)

        fun loadPrompt(): String {
            val stream = requireNotNull(
                CodingWorkerService::class.java.getResourceAsStream("/default-system-prompts/coding_worker_agent.md")
            ) { "Missing coding worker prompt" }
            return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
    }
}
