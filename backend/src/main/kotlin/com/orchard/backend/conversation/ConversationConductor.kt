package com.orchard.backend.conversation

import com.orchard.backend.resource.MachineResourceController
import com.orchard.backend.resource.ResourceAdmissionDecision
import com.orchard.backend.company.AUDIT_CONFORMING
import com.orchard.backend.company.CompanyProjectView
import com.orchard.backend.vector.DefaultModelExecutionProfiles
import com.orchard.backend.vector.ModelProfileResolver
import com.orchard.backend.vector.ModelProvider
import com.orchard.backend.vector.estimateModelTokens
import com.orchard.backend.vector.modelBindingFingerprint
import com.orchard.backend.workspace.MESSAGE_READY
import com.orchard.backend.workspace.RUN_STATE_CANCELLED
import com.orchard.backend.workspace.RUN_STATE_DONE
import com.orchard.backend.workspace.RUN_STATE_EVIDENCE_BLOCKED
import com.orchard.backend.workspace.WorkspaceStore
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val SPEECH_DISCUSS = "DISCUSS"
const val SPEECH_INSPECT = "INSPECT"
const val SPEECH_PROPOSE_OBJECTIVE = "PROPOSE_OBJECTIVE"
const val SPEECH_REVISE_OBJECTIVE = "REVISE_OBJECTIVE"
const val SPEECH_PROPOSE_DOMAIN_ACTION = "PROPOSE_DOMAIN_ACTION"
const val SPEECH_ADMIT_DOMAIN_ACTION = "ADMIT_DOMAIN_ACTION"
const val SPEECH_PAUSE_OBJECTIVE = "PAUSE_OBJECTIVE"
const val SPEECH_RESUME_OBJECTIVE = "RESUME_OBJECTIVE"
const val SPEECH_CANCEL_OBJECTIVE = "CANCEL_OBJECTIVE"
const val SPEECH_SET_PRIORITY = "SET_PRIORITY"
const val SPEECH_SET_DEPENDENCIES = "SET_DEPENDENCIES"
const val SPEECH_REQUEST_STATUS = "REQUEST_STATUS"
const val SPEECH_CLARIFY = "CLARIFY"

const val MESSAGE_INTENT_STANDARD = "STANDARD"
const val MESSAGE_INTENT_ADVISORY = "ADVISORY"

private val ADVISORY_FORBIDDEN_SPEECH_ACTS = setOf(
    SPEECH_ADMIT_DOMAIN_ACTION,
    SPEECH_REVISE_OBJECTIVE,
    SPEECH_PAUSE_OBJECTIVE,
    SPEECH_RESUME_OBJECTIVE,
    SPEECH_CANCEL_OBJECTIVE,
    SPEECH_SET_PRIORITY,
    SPEECH_SET_DEPENDENCIES,
)

const val OBJECTIVE_CONTROL_ADMIT = "ADMIT"
const val OBJECTIVE_CONTROL_PAUSE = "PAUSE"
const val OBJECTIVE_CONTROL_RESUME = "RESUME"
const val OBJECTIVE_CONTROL_CANCEL = "CANCEL"
const val OBJECTIVE_CONTROL_COMPLETE = "COMPLETE"
const val OBJECTIVE_CONTROL_SET_PRIORITY = "SET_PRIORITY"
const val OBJECTIVE_CONTROL_SET_DEPENDENCIES = "SET_DEPENDENCIES"

@Serializable
data class CreateConversationRequest(val title: String, val actor: String = "HUMAN")

@Serializable
data class SubmitConversationMessageRequest(
    val clientMessageId: String,
    val expectedSequence: Long,
    val content: String,
    val objectiveId: Long? = null,
    val actor: String = "HUMAN",
    val intent: String = MESSAGE_INTENT_STANDARD,
)

@Serializable
data class AdmitConversationCommandRequest(val commandHash: String, val actor: String = "HUMAN")

@Serializable
data class RejectConversationCommandRequest(val commandHash: String, val actor: String = "HUMAN")

@Serializable
data class ControlConversationObjectiveRequest(
    val action: String,
    val sourceMessageId: Long,
    val sourceMessageHash: String,
    val priority: Int? = null,
    val dependencyObjectiveIds: List<Long>? = null,
    val actor: String = "HUMAN",
)

@Serializable
data class ConversationListItem(
    val conversation: Conversation,
    val messageCount: Int,
    val objectiveCount: Int,
    val lastEventId: Long,
)

@Serializable
data class ConversationProjection(
    val conversation: Conversation,
    val messages: List<ConversationMessage>,
    val objectives: List<ConversationObjectiveRevision>,
    val commands: List<ConversationCommandView>,
    val summaries: List<ConversationSummaryRevision>,
    val activities: List<ConversationActivity>,
    val events: List<ConversationEvent>,
    val lastEventId: Long,
)

@Serializable
data class ConversationApiResponse(
    val status: String,
    val projection: ConversationProjection? = null,
    val diagnostic: String = "",
)

@Serializable
data class ConversationCommandView(
    val proposal: ConversationCommandProposal,
    val admission: ConversationCommandAdmission? = null,
    val executions: List<ConversationCommandExecution> = emptyList(),
)

enum class ConversationOperationStatus {
    CREATED,
    RECORDED,
    ALREADY_RECORDED,
    NOT_FOUND,
    STALE_SEQUENCE,
    INVALID_REQUEST,
    AMBIGUOUS_OBJECTIVE,
    ADMISSION_REQUIRED,
    REJECTED,
    MODEL_UNAVAILABLE,
    RESOURCE_UNAVAILABLE,
    STORAGE_UNAVAILABLE,
}

data class ConversationOperationResult(
    val status: ConversationOperationStatus,
    val projection: ConversationProjection? = null,
    val diagnostic: String = "",
) {
    fun toApiResponse() = ConversationApiResponse(status.name, projection, diagnostic)
}

@Serializable
data class ObjectiveCandidate(
    val projectId: Int? = null,
    val title: String,
    val outcome: String,
    val constraints: List<String> = emptyList(),
    val priority: Int = 50,
    val dependencyObjectiveIds: List<Long> = emptyList(),
)

@Serializable
data class ConversationInterpretation(
    val speechAct: String,
    val response: String,
    val objective: ObjectiveCandidate? = null,
    val capabilityId: String? = null,
    val payloadJson: String? = null,
    val commandId: Long? = null,
    val commandHash: String? = null,
)

data class InterpretedConversationTurn(
    val interpretation: ConversationInterpretation,
    val providerFingerprint: String? = null,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val resourceDecision: String? = null,
    val modelProvenance: ConversationModelProvenance? = null,
)

@Serializable
data class ConversationContextManifest(
    val conversationId: Long,
    val selectedObjectiveId: Long? = null,
    val recentMessages: List<ConversationMessage>,
    val objectives: List<ConversationObjectiveRevision>,
    val commandStates: List<ConversationContextCommand>,
    val summaries: List<ConversationSummaryRevision>,
    val capabilities: List<ConversationCapabilityDescriptor>,
    val conversationTitle: String = "",
)

@Serializable
data class ConversationContextCommand(
    val commandId: Long,
    val objectiveId: Long? = null,
    val capabilityId: String,
    val commandHash: String,
    val admitted: Boolean,
    val state: String,
    val downstreamType: String? = null,
    val downstreamId: String? = null,
)

@Serializable
data class ConversationCapabilityDescriptor(
    val id: String,
    val description: String,
    val mutation: Boolean,
    val payloadSchema: String,
    val allowedObjectiveStates: Set<String>,
    val owningService: String,
    val admissionRule: String,
    val resultType: String,
    val idempotencyStrategy: String,
)

@Serializable
data class ConversationCapabilityResult(
    val success: Boolean,
    val summary: String,
    val downstreamType: String,
    val downstreamId: String,
    val downstreamHash: String,
    val repositoryRevision: String? = null,
)

interface ConversationCapability {
    val descriptor: ConversationCapabilityDescriptor
    suspend fun preflight(
        payloadJson: String,
        objective: ConversationObjectiveRevision?,
    ): ConversationCapabilityResult? = null
    suspend fun reconcile(
        command: ConversationCommandProposal,
        objective: ConversationObjectiveRevision?,
    ): ConversationCapabilityResult? = null
    suspend fun execute(
        payloadJson: String,
        objective: ConversationObjectiveRevision?,
        command: ConversationCommandProposal,
    ): ConversationCapabilityResult
}

class ConversationCapabilityRegistry(capabilities: List<ConversationCapability>) {
    private val registered = capabilities.associateBy { it.descriptor.id }

    init {
        require(registered.size == capabilities.size) { "Conversation capability IDs must be unique" }
        require(capabilities.all { capability ->
            capability.descriptor.let {
                it.owningService.isNotBlank() && it.admissionRule.isNotBlank() &&
                    it.resultType.isNotBlank() && it.idempotencyStrategy.isNotBlank() &&
                    (it.mutation || it.admissionRule == "NONE")
            }
        }) { "Conversation capability authority metadata is incomplete" }
    }

    fun descriptors(): List<ConversationCapabilityDescriptor> = registered.values.map { it.descriptor }.sortedBy { it.id }
    fun resolve(id: String): ConversationCapability? = registered[id]
}

fun interface ConversationInterpreter {
    suspend fun interpret(context: ConversationContextManifest, message: ConversationMessage): InterpretedConversationTurn
}

class ModelConversationInterpreter(
    private val providers: List<ModelProvider>,
    private val resourceController: MachineResourceController = MachineResourceController.unrestricted(),
    private val json: Json = Json { encodeDefaults = true },
) : ConversationInterpreter {
    private val strictJson = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    override suspend fun interpret(
        context: ConversationContextManifest,
        message: ConversationMessage,
    ): InterpretedConversationTurn {
        val profile = DefaultModelExecutionProfiles.boundedConversationConductor
        val provider = ModelProfileResolver.resolve(profile, providers)
        val prompt = buildString {
            append(SYSTEM_PROMPT)
            append("\n\nAuthoritative context manifest:\n")
            append(json.encodeToString(context))
            append("\n\nNew user message:\n")
            append(json.encodeToString(message))
        }
        val promptTokens = estimateModelTokens(prompt)
        require(promptTokens <= profile.inputBudgetTokens) { "Conversation context budget exceeded" }
        val admission = resourceController.tryAcquire(provider.resourceDemand(profile, promptTokens))
        val lease = admission.lease ?: error(buildString {
            if (admission.evidence.decision == ResourceAdmissionDecision.TELEMETRY_UNAVAILABLE) {
                append("Conversation resource telemetry unavailable")
            } else {
                append("Conversation resource capacity unavailable")
            }
            append(": ${admission.evidence.decision} - ${admission.evidence.reason}")
        })
        val startedAt = System.nanoTime()
        val generation = lease.use {
            provider.executeConversation(
                prompt,
                profile.outputBudgetTokens,
                promptTokens + profile.outputBudgetTokens,
            )
        }
        require(generation.promptTokens <= profile.inputBudgetTokens &&
            generation.completionTokens <= profile.outputBudgetTokens &&
            estimateModelTokens(generation.text) <= profile.outputBudgetTokens) { "Conversation model budget exceeded" }
        val interpretation = strictJson.decodeFromString<ConversationInterpretation>(generation.text)
        val binding = provider.bindingProfile()
        val fingerprint = modelBindingFingerprint(binding)
        return InterpretedConversationTurn(
            interpretation,
            fingerprint,
            generation.promptTokens,
            generation.completionTokens,
            admission.evidence.decision.name,
            ConversationModelProvenance(
                executionProfileId = profile.id,
                executionProfileVersion = profile.version,
                providerFingerprint = fingerprint,
                bindingId = binding.bindingId,
                provider = binding.provider,
                model = binding.model,
                modelDigest = binding.modelDigest,
                configurationHash = conversationAuthorityHash(json.encodeToString<Map<String, String>>(binding.configuration.toSortedMap())),
                promptHash = conversationAuthorityHash(prompt),
                outputHash = conversationAuthorityHash(generation.text),
                promptTokens = generation.promptTokens,
                completionTokens = generation.completionTokens,
                latencyMillis = (System.nanoTime() - startedAt) / 1_000_000,
                inputBudgetTokens = profile.inputBudgetTokens,
                outputBudgetTokens = profile.outputBudgetTokens,
                resourceDecision = admission.evidence.decision.name,
            ),
        )
    }

    private companion object {
        val SYSTEM_PROMPT = """
            You are Orchard's conversational conductor. Return exactly one JSON object matching ConversationInterpretation.
            The top-level keys are exactly speechAct, response, objective, capabilityId, payloadJson, commandId, and commandHash. Return no other top-level keys.
            payloadJson must be null or a JSON string containing the selected capability payload, escaped as a string inside the outer object. Never return a top-level payload key or a nested payload object.
            Select only a listed speech act and capability. Never invent IDs, routes, shell commands, authority, or admission.
            Use PROPOSE_DOMAIN_ACTION when the user directly requests a listed capability. A domain action does not require a separate objective; set objective to null unless the user explicitly proposes one.
            A credential-free HTTP(S) Git URL is sufficient for ONBOARD_REPOSITORY: preserve it exactly as location, use source GIT_URL, derive projectTitle from the repository name, and use projectId 0 when no existing project is identified.
            A valid onboarding response has this exact outer shape: {"speechAct":"PROPOSE_DOMAIN_ACTION","response":"Proposing repository onboarding.","objective":null,"capabilityId":"ONBOARD_REPOSITORY","payloadJson":"{\"source\":\"GIT_URL\",\"location\":\"https://github.com/example/repository\",\"projectTitle\":\"repository\",\"projectId\":0}","commandId":null,"commandHash":null}
            The first output character must be { and the last output character must be }. Do not emit analysis or reasoning.
            Mutating domain actions are proposals only. Use ADMIT_DOMAIN_ACTION only when the user cites one exact command ID and hash.
            Use PROPOSE_OBJECTIVE for a new bounded outcome. Use CLARIFY whenever objective or authority identity is ambiguous.
            Keep response concise and distinguish observed authority from proposed action.
        """.trimIndent()
    }
}

class ConversationContextCompiler(private val maxRecentMessages: Int = 24) {
    fun compile(
        events: List<ConversationEvent>,
        conversationId: Long,
        selectedObjectiveId: Long?,
        capabilities: List<ConversationCapabilityDescriptor>,
        sourceMessage: ConversationMessage? = null,
    ): ConversationContextManifest {
        val conversation = events.mapNotNull { it.conversation }.single { it.conversationId == conversationId }
        val messages = events.mapNotNull { it.message }.filter { it.conversationId == conversationId }
        val objectives = latestObjectives(events, conversationId)
        val commands = events.mapNotNull { it.command }.filter { it.conversationId == conversationId }
        val admissions = events.mapNotNull { it.admission }.associateBy { it.commandId }
        val executions = events.mapNotNull { it.execution }.groupBy { it.commandId }
        val isolateConversationHistory = selectedObjectiveId == null &&
            capabilities.any { it.id == CAPABILITY_ONBOARD_REPOSITORY } &&
            sourceMessage?.let(::isExplicitRepositoryOnboardingAction) == true
        return ConversationContextManifest(
            conversationId = conversationId,
            selectedObjectiveId = selectedObjectiveId,
            recentMessages = if (isolateConversationHistory) listOf(sourceMessage) else messages.takeLast(maxRecentMessages),
            objectives = objectives,
            commandStates = commands.map { command ->
                val last = executions[command.commandId].orEmpty().maxByOrNull { it.executionId }
                ConversationContextCommand(
                    command.commandId,
                    command.objectiveId,
                    command.capabilityId,
                    command.hash,
                    admissions.containsKey(command.commandId),
                    last?.state ?: COMMAND_PROPOSED,
                    last?.downstreamType,
                    last?.downstreamId,
                )
            }.takeLast(32),
            summaries = if (isolateConversationHistory) emptyList() else events.mapNotNull { it.summary }
                .filter { it.conversationId == conversationId }
                .groupBy { it.summaryId }
                .map { (_, revisions) -> revisions.maxBy { it.revision } },
            capabilities = capabilities,
            conversationTitle = conversation.title,
        )
    }
}

private fun isExplicitRepositoryOnboardingAction(message: ConversationMessage): Boolean =
    ONBOARDING_ACTION.containsMatchIn(message.content) &&
        (HTTP_REPOSITORY_SOURCE.containsMatchIn(message.content) || ABSOLUTE_REPOSITORY_SOURCE.containsMatchIn(message.content))

class ConversationConductorService(
    private val store: ConversationStore,
    private val interpreter: ConversationInterpreter,
    private val capabilities: ConversationCapabilityRegistry,
    private val contextCompiler: ConversationContextCompiler = ConversationContextCompiler(),
    private val now: () -> String = { Instant.now().toString() },
) {
    private val authorityLock = Any()
    private val objectiveLocks = ConcurrentHashMap<Long, Mutex>()

    fun list(): List<ConversationListItem> = synchronized(authorityLock) {
        val events = store.events()
        events.mapNotNull { it.conversation }.map { conversation ->
            ConversationListItem(
                conversation,
                events.count { it.message?.conversationId == conversation.conversationId },
                latestObjectives(events, conversation.conversationId).size,
                events.lastOrNull()?.eventId ?: 0,
            )
        }
    }

    fun projection(conversationId: Long, afterEventId: Long = 0): ConversationProjection? = synchronized(authorityLock) {
        project(store.events(), conversationId, afterEventId)
    }

    fun create(request: CreateConversationRequest): ConversationOperationResult = synchronized(authorityLock) {
        if (request.title.isBlank() || request.title.encodeToByteArray().size > 512 || request.actor.isBlank()) {
            return@synchronized ConversationOperationResult(ConversationOperationStatus.INVALID_REQUEST)
        }
        val events = store.events()
        val conversation = newConversation(Conversation(
            conversationId = events.mapNotNull { it.conversation }.maxOfOrNull { it.conversationId }?.plus(1) ?: 1,
            title = request.title.trim(),
            actor = request.actor.trim(),
            createdAt = now(),
            hash = "",
        ))
        runCatching { store.appendConversation(conversation) }.fold(
            onSuccess = { ConversationOperationResult(ConversationOperationStatus.CREATED, project(store.events(), conversation.conversationId)) },
            onFailure = { ConversationOperationResult(ConversationOperationStatus.STORAGE_UNAVAILABLE, diagnostic = it.message.orEmpty()) },
        )
    }

    suspend fun submitMessage(
        conversationId: Long,
        request: SubmitConversationMessageRequest,
    ): ConversationOperationResult {
        val recorded = appendUserMessage(conversationId, request)
        if (recorded.status != ConversationOperationStatus.RECORDED) return recorded
        val userMessage = requireNotNull(recorded.projection).messages.last { it.clientMessageId == request.clientMessageId }
        val events = synchronized(authorityLock) {
            appendRollingSummaryLocked(conversationId)
            store.events()
        }
        val objective = resolveObjective(events, conversationId, request.objectiveId)
        val context = contextCompiler.compile(
            events,
            conversationId,
            objective?.objectiveId,
            capabilities.descriptors(),
            sourceMessage = userMessage,
        )
        val turn = runCatching { interpreter.interpret(context, userMessage) }.getOrElse { error ->
            appendAssistant(conversationId, userMessage, objective?.objectiveId, "I could not interpret that request safely: ${error.message.orEmpty()}")
            return ConversationOperationResult(ConversationOperationStatus.MODEL_UNAVAILABLE, projection(conversationId), error.message.orEmpty())
        }
        turn.providerFingerprint?.let { fingerprint ->
            appendActivity(conversationId, objective?.objectiveId, ACTIVITY_INFO,
                "Conversation turn interpreted with ${turn.promptTokens} input units, ${turn.completionTokens} output units, and resource decision ${turn.resourceDecision}.",
                "MODEL_BINDING", fingerprint, fingerprint, turn.modelProvenance)
        }
        return applyInterpretation(userMessage, objective, turn.interpretation, request.intent)
    }

    suspend fun admitCommand(commandId: Long, request: AdmitConversationCommandRequest): ConversationOperationResult {
        val events = synchronized(authorityLock) { store.events() }
        val command = events.mapNotNull { it.command }.singleOrNull { it.commandId == commandId }
            ?: return ConversationOperationResult(ConversationOperationStatus.NOT_FOUND)
        if (command.hash != request.commandHash || request.actor.isBlank()) {
            return ConversationOperationResult(ConversationOperationStatus.REJECTED, projection(command.conversationId), "Command hash or actor is invalid.")
        }
        val terminal = events.mapNotNull { it.execution }.filter { it.commandId == commandId }.maxByOrNull { it.executionId }
        if (terminal?.state == COMMAND_REJECTED) {
            return ConversationOperationResult(
                ConversationOperationStatus.REJECTED,
                projection(command.conversationId),
                "Rejected commands cannot be admitted.",
            )
        }
        if (events.any { it.admission?.commandId == commandId }) {
            return if (terminal?.state == COMMAND_CORRELATED) {
                ConversationOperationResult(ConversationOperationStatus.ALREADY_RECORDED, projection(command.conversationId))
            } else execute(command)
        }
        synchronized(authorityLock) {
            val current = store.events()
            if (current.none { it.admission?.commandId == commandId }) {
                val admission = newConversationAdmission(ConversationCommandAdmission(
                    nextAdmissionId(current), commandId, command.hash, request.actor.trim(), now(), "",
                ))
                store.appendAdmission(admission)
                appendExecutionLocked(command, COMMAND_ADMITTED)
            }
        }
        return execute(command)
    }

    fun rejectCommand(commandId: Long, request: RejectConversationCommandRequest): ConversationOperationResult = synchronized(authorityLock) {
        val events = store.events()
        val command = events.mapNotNull { it.command }.singleOrNull { it.commandId == commandId }
            ?: return@synchronized ConversationOperationResult(ConversationOperationStatus.NOT_FOUND)
        if (command.hash != request.commandHash || request.actor.isBlank()) {
            return@synchronized ConversationOperationResult(
                ConversationOperationStatus.REJECTED,
                project(events, command.conversationId),
                "Command hash or actor is invalid.",
            )
        }
        val executions = events.mapNotNull { it.execution }.filter { it.commandId == commandId }
        if (executions.lastOrNull()?.state == COMMAND_REJECTED) {
            return@synchronized ConversationOperationResult(
                ConversationOperationStatus.ALREADY_RECORDED,
                project(events, command.conversationId),
            )
        }
        if (events.any { it.admission?.commandId == commandId } || executions.isNotEmpty()) {
            return@synchronized ConversationOperationResult(
                ConversationOperationStatus.REJECTED,
                project(events, command.conversationId),
                "Only a pending unadmitted command can be rejected.",
            )
        }
        appendExecutionLocked(command, COMMAND_REJECTED, diagnostic = "Rejected by ${request.actor.trim()}.")
        val source = store.events().mapNotNull { it.message }.single { it.messageId == command.sourceMessageId }
        appendAssistantLocked(source, command.objectiveId, "Dismissed ${command.capabilityId} proposal ${command.commandId}.")
        ConversationOperationResult(ConversationOperationStatus.RECORDED, project(store.events(), command.conversationId))
    }

    suspend fun reconcilePending(): Int {
        val events = synchronized(authorityLock) { store.events() }
        val admitted = events.mapNotNull { it.admission }.map { it.commandId }.toSet()
        val terminalByCommand = events.mapNotNull { it.execution }.groupBy { it.commandId }
            .mapValues { (_, executions) -> executions.maxBy { it.executionId } }
        val pending = events.mapNotNull { it.command }.filter { command ->
            command.commandId in admitted && terminalByCommand[command.commandId]?.state == COMMAND_DISPATCHED
        }
        var reconciled = 0
        pending.forEach { command ->
            val result = execute(command)
            if (result.status in setOf(ConversationOperationStatus.RECORDED, ConversationOperationStatus.ALREADY_RECORDED)) {
                reconciled++
            }
        }
        return reconciled
    }

    fun dispatchableRunIds(runIds: Collection<Long>): List<Long> = synchronized(authorityLock) {
        val events = store.events()
        val objectives = latestObjectives(events).associateBy { it.objectiveId }
        val commands = events.mapNotNull { it.command }.associateBy { it.commandId }
        val runObjectives = events.mapNotNull { it.execution }
            .filter { it.state == COMMAND_CORRELATED && it.downstreamType == "WORKFLOW_RUN" }
            .mapNotNull { execution ->
                val runId = execution.downstreamId?.toLongOrNull() ?: return@mapNotNull null
                runId to commands[execution.commandId]?.objectiveId
            }
            .toMap()
        runIds.filter { runId ->
            val objectiveId = runObjectives[runId]
            val objective = objectiveId?.let(objectives::get)
            objectiveId == null || objective != null && objective.state in executableStates &&
                objective.dependencyObjectiveIds.all { dependencyId -> objectives[dependencyId]?.state == OBJECTIVE_COMPLETED }
        }.sortedWith(
            compareByDescending<Long> { runId -> runObjectives[runId]?.let(objectives::get)?.priority ?: 50 }
                .thenBy { it }
        )
    }

    fun projectWorkspaceActivity(workspace: WorkspaceStore): Int = synchronized(authorityLock) {
        var events = store.events()
        val snapshot = workspace.snapshot(MESSAGE_READY)
        val commands = events.mapNotNull { it.command }.associateBy { it.commandId }
        val correlatedRuns = events.mapNotNull { it.execution }
            .filter { it.state == COMMAND_CORRELATED && it.downstreamType == "WORKFLOW_RUN" }
            .groupBy { it.commandId }
            .mapNotNull { (commandId, executions) ->
                val command = commands[commandId] ?: return@mapNotNull null
                val execution = executions.maxBy { it.executionId }
                val runId = execution.downstreamId?.toLongOrNull() ?: return@mapNotNull null
                Triple(command, execution, runId)
            }
        var projected = 0
        correlatedRuns.forEach { (command, _, runId) ->
            val run = snapshot.workflowRuns.singleOrNull { it.runId == runId } ?: return@forEach
            val authorityHash = conversationAuthorityHash(Json.encodeToString(run))
            val alreadyProjected = events.mapNotNull { it.activity }.any {
                it.authorityType == "WORKFLOW_RUN" && it.authorityId == runId.toString() && it.authorityHash == authorityHash
            }
            if (!alreadyProjected) {
                val kind = when (run.state) {
                    RUN_STATE_DONE, RUN_STATE_CANCELLED -> ACTIVITY_TERMINAL
                    RUN_STATE_EVIDENCE_BLOCKED -> ACTIVITY_ATTENTION
                    else -> ACTIVITY_INFO
                }
                store.appendActivity(newConversationActivity(ConversationActivity(
                    nextActivityId(events), command.conversationId, command.objectiveId, kind,
                    "Workflow run $runId is ${run.state} with ${run.evidence.size} evidence records and ${run.attempts.size} attempts.",
                    "WORKFLOW_RUN", runId.toString(), authorityHash, now(), "",
                )))
                events = store.events()
                projected++
            }
            val objective = latestObjectives(events, command.conversationId).singleOrNull { it.objectiveId == command.objectiveId }
                ?: return@forEach
            val projectedState = when (run.state) {
                RUN_STATE_DONE -> OBJECTIVE_ACTIVE
                RUN_STATE_CANCELLED -> OBJECTIVE_CANCELLED
                RUN_STATE_EVIDENCE_BLOCKED -> OBJECTIVE_BLOCKED
                else -> OBJECTIVE_ACTIVE
            }
            if (objective.state != projectedState &&
                objective.state !in setOf(OBJECTIVE_COMPLETED, OBJECTIVE_CANCELLED, OBJECTIVE_SUPERSEDED) &&
                !(objective.state == OBJECTIVE_PAUSED && projectedState == OBJECTIVE_ACTIVE)) {
                val source = events.mapNotNull { it.message }.single { it.messageId == command.sourceMessageId }
                store.appendObjective(newConversationObjective(objective.copy(
                    revision = objective.revision + 1,
                    state = projectedState,
                    sourceMessageId = source.messageId,
                    sourceMessageHash = source.hash,
                    actor = "ORCHARD_PROJECTION",
                    createdAt = now(),
                    previousHash = objective.hash,
                    hash = "",
                )))
                events = store.events()
                projected++
            }
        }
        projected
    }

    fun projectCompanyActivity(companyViews: List<CompanyProjectView>): Int = synchronized(authorityLock) {
        var events = store.events()
        val commands = events.mapNotNull { it.command }.associateBy { it.commandId }
        val correlatedRuns = events.mapNotNull { it.execution }
            .filter { it.state == COMMAND_CORRELATED && it.downstreamType == "WORKFLOW_RUN" }
            .mapNotNull { execution ->
                val command = commands[execution.commandId] ?: return@mapNotNull null
                val runId = execution.downstreamId?.toLongOrNull() ?: return@mapNotNull null
                command to runId
            }
        var projected = 0
        correlatedRuns.forEach { (command, runId) ->
            val project = companyViews.singleOrNull { view ->
                view.audits.any { it.runId == runId } || view.acceptances.any { it.runId == runId } ||
                    view.promotions.any { it.runId == runId }
            } ?: return@forEach
            project.audits.filter { it.runId == runId }.forEach { audit ->
                if (events.none { it.activity?.let { activity ->
                        activity.authorityType == "COMPANY_AUDIT" && activity.authorityId == audit.auditId.toString() &&
                            activity.authorityHash == audit.hash
                    } == true }) {
                    store.appendActivity(newConversationActivity(ConversationActivity(
                        nextActivityId(events), command.conversationId, command.objectiveId,
                        if (audit.status == AUDIT_CONFORMING) ACTIVITY_INFO else ACTIVITY_ATTENTION,
                        "${audit.role} audit ${audit.auditId} resolved ${audit.status} for revision ${audit.candidateRevision}.",
                        "COMPANY_AUDIT", audit.auditId.toString(), audit.hash, now(), "",
                    )))
                    events = store.events()
                    projected++
                }
            }
            project.acceptances.filter { it.runId == runId }.forEach { acceptance ->
                if (events.none { it.activity?.let { activity ->
                        activity.authorityType == "COMPANY_ACCEPTANCE" && activity.authorityId == acceptance.acceptanceId.toString() &&
                            activity.authorityHash == acceptance.hash
                    } == true }) {
                    store.appendActivity(newConversationActivity(ConversationActivity(
                        nextActivityId(events), command.conversationId, command.objectiveId, ACTIVITY_INFO,
                        "Company acceptance ${acceptance.acceptanceId} admitted revision ${acceptance.candidateRevision}.",
                        "COMPANY_ACCEPTANCE", acceptance.acceptanceId.toString(), acceptance.hash, now(), "",
                    )))
                    events = store.events()
                    projected++
                }
            }
            project.promotions.filter { it.runId == runId }.forEach { promotion ->
                if (events.none { it.activity?.let { activity ->
                        activity.authorityType == "LOCAL_PROMOTION" && activity.authorityId == promotion.promotionId.toString() &&
                            activity.authorityHash == promotion.hash
                    } == true }) {
                    store.appendActivity(newConversationActivity(ConversationActivity(
                        nextActivityId(events), command.conversationId, command.objectiveId, ACTIVITY_TERMINAL,
                        "Local promotion ${promotion.promotionId} completed at ${promotion.destinationRevision}.",
                        "LOCAL_PROMOTION", promotion.promotionId.toString(), promotion.hash, now(), "",
                    )))
                    events = store.events()
                    projected++
                }
                val objective = latestObjectives(events, command.conversationId)
                    .singleOrNull { it.objectiveId == command.objectiveId } ?: return@forEach
                if (objective.state !in setOf(OBJECTIVE_COMPLETED, OBJECTIVE_CANCELLED, OBJECTIVE_SUPERSEDED)) {
                    val source = events.mapNotNull { it.message }.single { it.messageId == command.sourceMessageId }
                    store.appendObjective(newConversationObjective(objective.copy(
                        revision = objective.revision + 1,
                        state = OBJECTIVE_COMPLETED,
                        sourceMessageId = source.messageId,
                        sourceMessageHash = source.hash,
                        actor = "ORCHARD_PROJECTION",
                        createdAt = now(),
                        previousHash = objective.hash,
                        hash = "",
                    )))
                    events = store.events()
                    projected++
                }
            }
        }
        projected
    }

    fun controlObjective(objectiveId: Long, request: ControlConversationObjectiveRequest): ConversationOperationResult =
        synchronized(authorityLock) {
            val events = store.events()
            val prior = latestObjectives(events).singleOrNull { it.objectiveId == objectiveId }
                ?: return@synchronized ConversationOperationResult(ConversationOperationStatus.NOT_FOUND)
            val source = events.mapNotNull { it.message }.singleOrNull {
                it.messageId == request.sourceMessageId && it.conversationId == prior.conversationId && it.hash == request.sourceMessageHash
            } ?: return@synchronized ConversationOperationResult(ConversationOperationStatus.REJECTED, project(events, prior.conversationId), "Source message is invalid.")
            val next = controlledObjective(prior, request) ?: return@synchronized ConversationOperationResult(
                ConversationOperationStatus.REJECTED, project(events, prior.conversationId), "Objective transition is invalid.",
            )
            val revision = newConversationObjective(next.copy(
                revision = prior.revision + 1,
                sourceMessageId = source.messageId,
                sourceMessageHash = source.hash,
                actor = request.actor,
                createdAt = now(),
                previousHash = prior.hash,
                hash = "",
            ))
            runCatching { store.appendObjective(revision) }.fold(
                onSuccess = { ConversationOperationResult(ConversationOperationStatus.RECORDED, project(store.events(), prior.conversationId)) },
                onFailure = { ConversationOperationResult(ConversationOperationStatus.REJECTED, project(store.events(), prior.conversationId), it.message.orEmpty()) },
            )
        }

    private fun appendUserMessage(conversationId: Long, request: SubmitConversationMessageRequest): ConversationOperationResult =
        synchronized(authorityLock) {
            val events = store.events()
            val conversation = events.mapNotNull { it.conversation }.singleOrNull { it.conversationId == conversationId }
                ?: return@synchronized ConversationOperationResult(ConversationOperationStatus.NOT_FOUND)
            val duplicate = events.mapNotNull { it.message }.singleOrNull {
                it.conversationId == conversationId && it.clientMessageId == request.clientMessageId
            }
            if (duplicate != null) return@synchronized ConversationOperationResult(
                ConversationOperationStatus.ALREADY_RECORDED, project(events, conversationId),
            )
            val sequence = events.count { it.message?.conversationId == conversationId } + 1L
            if (request.expectedSequence != sequence) return@synchronized ConversationOperationResult(
                ConversationOperationStatus.STALE_SEQUENCE, project(events, conversationId), "Expected message sequence $sequence.",
            )
            if (
                request.content.isBlank() || request.actor.isBlank() ||
                request.intent !in setOf(MESSAGE_INTENT_STANDARD, MESSAGE_INTENT_ADVISORY)
            ) {
                return@synchronized ConversationOperationResult(ConversationOperationStatus.INVALID_REQUEST, project(events, conversationId))
            }
            if (request.objectiveId != null && latestObjectives(events, conversationId).none { it.objectiveId == request.objectiveId }) {
                return@synchronized ConversationOperationResult(ConversationOperationStatus.NOT_FOUND, project(events, conversationId))
            }
            val message = newConversationMessage(ConversationMessage(
                messageId = nextMessageId(events),
                conversationId = conversation.conversationId,
                sequence = sequence,
                clientMessageId = request.clientMessageId,
                role = MESSAGE_ROLE_USER,
                content = request.content.trim(),
                objectiveId = request.objectiveId,
                actor = request.actor.trim(),
                createdAt = now(),
                hash = "",
            ))
            runCatching { store.appendMessage(message) }.fold(
                onSuccess = { ConversationOperationResult(ConversationOperationStatus.RECORDED, project(store.events(), conversationId)) },
                onFailure = { ConversationOperationResult(ConversationOperationStatus.STORAGE_UNAVAILABLE, project(store.events(), conversationId), it.message.orEmpty()) },
            )
        }

    private suspend fun applyInterpretation(
        source: ConversationMessage,
        selectedObjective: ConversationObjectiveRevision?,
        interpretation: ConversationInterpretation,
        messageIntent: String,
    ): ConversationOperationResult {
        if (messageIntent == MESSAGE_INTENT_ADVISORY && interpretation.speechAct in ADVISORY_FORBIDDEN_SPEECH_ACTS) {
            appendAssistant(
                source.conversationId,
                source,
                selectedObjective?.objectiveId,
                "This advisory request can diagnose and propose work, but it cannot admit commands or control objective authority.",
            )
            return ConversationOperationResult(
                ConversationOperationStatus.REJECTED,
                projection(source.conversationId),
                "Advisory messages cannot admit commands or control objectives.",
            )
        }
        return when (interpretation.speechAct) {
        SPEECH_DISCUSS, SPEECH_CLARIFY -> {
            appendAssistant(source.conversationId, source, selectedObjective?.objectiveId, interpretation.response)
            ConversationOperationResult(ConversationOperationStatus.RECORDED, projection(source.conversationId))
        }
        SPEECH_PROPOSE_OBJECTIVE -> proposeObjective(source, interpretation)
        SPEECH_REVISE_OBJECTIVE -> reviseObjective(source, selectedObjective, interpretation.objective)
        SPEECH_REQUEST_STATUS, SPEECH_INSPECT -> proposeOrExecuteCommand(source, selectedObjective, interpretation, readOnlyRequired = true)
        SPEECH_PROPOSE_DOMAIN_ACTION -> proposeOrExecuteCommand(source, selectedObjective, interpretation, readOnlyRequired = false)
        SPEECH_PAUSE_OBJECTIVE -> controlFromSpeech(source, selectedObjective, OBJECTIVE_CONTROL_PAUSE)
        SPEECH_RESUME_OBJECTIVE -> controlFromSpeech(source, selectedObjective, OBJECTIVE_CONTROL_RESUME)
        SPEECH_CANCEL_OBJECTIVE -> controlFromSpeech(source, selectedObjective, OBJECTIVE_CONTROL_CANCEL)
        SPEECH_SET_PRIORITY -> controlFromSpeech(
            source, selectedObjective, OBJECTIVE_CONTROL_SET_PRIORITY, priority = interpretation.objective?.priority,
        )
        SPEECH_SET_DEPENDENCIES -> controlFromSpeech(
            source, selectedObjective, OBJECTIVE_CONTROL_SET_DEPENDENCIES,
            dependencyObjectiveIds = interpretation.objective?.dependencyObjectiveIds,
        )
        SPEECH_ADMIT_DOMAIN_ACTION -> {
            val commandId = interpretation.commandId
            val commandHash = interpretation.commandHash
            if (commandId == null || commandHash == null) {
                appendAssistant(source.conversationId, source, selectedObjective?.objectiveId, "Name one exact command ID and hash to admit it.")
                ConversationOperationResult(ConversationOperationStatus.REJECTED, projection(source.conversationId))
            } else admitCommand(commandId, AdmitConversationCommandRequest(commandHash, source.actor))
        }
        else -> {
            appendAssistant(source.conversationId, source, selectedObjective?.objectiveId, "That speech act is outside Orchard's closed vocabulary.")
            ConversationOperationResult(ConversationOperationStatus.REJECTED, projection(source.conversationId))
        }
    }
    }

    private fun proposeObjective(source: ConversationMessage, interpretation: ConversationInterpretation): ConversationOperationResult =
        synchronized(authorityLock) {
            val candidate = interpretation.objective
            if (candidate == null || candidate.title.isBlank() || candidate.outcome.isBlank()) {
                appendAssistantLocked(source, null, "I need a bounded title and outcome before I can materialize that objective.")
                return@synchronized ConversationOperationResult(ConversationOperationStatus.REJECTED, project(store.events(), source.conversationId))
            }
            val events = store.events()
            val objective = newConversationObjective(ConversationObjectiveRevision(
                objectiveId = nextObjectiveId(events),
                revision = 1,
                conversationId = source.conversationId,
                projectId = candidate.projectId,
                title = candidate.title.trim(),
                outcome = candidate.outcome.trim(),
                constraints = candidate.constraints.map(String::trim),
                priority = candidate.priority,
                dependencyObjectiveIds = candidate.dependencyObjectiveIds,
                state = OBJECTIVE_AWAITING_ADMISSION,
                sourceMessageId = source.messageId,
                sourceMessageHash = source.hash,
                actor = "MODEL_PROPOSAL",
                createdAt = now(),
                hash = "",
            ))
            runCatching { store.appendObjective(objective) }.onFailure {
                appendAssistantLocked(source, null, "The objective proposal was rejected: ${it.message.orEmpty()}")
                return@synchronized ConversationOperationResult(ConversationOperationStatus.REJECTED, project(store.events(), source.conversationId), it.message.orEmpty())
            }
            appendAssistantLocked(source, objective.objectiveId, interpretation.response.ifBlank {
                "Objective ${objective.objectiveId} is awaiting explicit admission."
            })
            ConversationOperationResult(ConversationOperationStatus.ADMISSION_REQUIRED, project(store.events(), source.conversationId))
        }

    private fun reviseObjective(
        source: ConversationMessage,
        selected: ConversationObjectiveRevision?,
        candidate: ObjectiveCandidate?,
    ): ConversationOperationResult = synchronized(authorityLock) {
        if (selected == null || candidate == null || candidate.title.isBlank() || candidate.outcome.isBlank()) {
            appendAssistantLocked(source, selected?.objectiveId, "Select one objective and provide its complete revised outcome.")
            return@synchronized ConversationOperationResult(ConversationOperationStatus.AMBIGUOUS_OBJECTIVE, project(store.events(), source.conversationId))
        }
        val revision = newConversationObjective(selected.copy(
            revision = selected.revision + 1,
            projectId = candidate.projectId ?: selected.projectId,
            title = candidate.title.trim(),
            outcome = candidate.outcome.trim(),
            constraints = candidate.constraints.map(String::trim),
            priority = candidate.priority,
            dependencyObjectiveIds = candidate.dependencyObjectiveIds,
            state = OBJECTIVE_AWAITING_ADMISSION,
            sourceMessageId = source.messageId,
            sourceMessageHash = source.hash,
            actor = source.actor,
            createdAt = now(),
            previousHash = selected.hash,
            hash = "",
        ))
        return@synchronized runCatching { store.appendObjective(revision) }.fold(
            onSuccess = {
                appendAssistantLocked(source, revision.objectiveId, "Objective ${revision.objectiveId} revision ${revision.revision} awaits admission.")
                ConversationOperationResult(ConversationOperationStatus.ADMISSION_REQUIRED, project(store.events(), source.conversationId))
            },
            onFailure = { ConversationOperationResult(ConversationOperationStatus.REJECTED, project(store.events(), source.conversationId), it.message.orEmpty()) },
        )
    }

    private fun controlFromSpeech(
        source: ConversationMessage,
        selected: ConversationObjectiveRevision?,
        action: String,
        priority: Int? = null,
        dependencyObjectiveIds: List<Long>? = null,
    ): ConversationOperationResult {
        if (selected == null) {
            appendAssistant(source.conversationId, source, null, "Select one exact objective before applying that control.")
            return ConversationOperationResult(ConversationOperationStatus.AMBIGUOUS_OBJECTIVE, projection(source.conversationId))
        }
        val result = controlObjective(selected.objectiveId, ControlConversationObjectiveRequest(
            action, source.messageId, source.hash, priority, dependencyObjectiveIds, source.actor,
        ))
        if (result.status == ConversationOperationStatus.RECORDED) {
            appendAssistant(source.conversationId, source, selected.objectiveId, "Objective ${selected.objectiveId} control $action was recorded.")
            return result.copy(projection = projection(source.conversationId))
        }
        return result
    }

    private suspend fun proposeOrExecuteCommand(
        source: ConversationMessage,
        selectedObjective: ConversationObjectiveRevision?,
        interpretation: ConversationInterpretation,
        readOnlyRequired: Boolean,
    ): ConversationOperationResult {
        val capabilityId = interpretation.capabilityId.orEmpty()
        val capability = capabilities.resolve(capabilityId)
        if (capability == null || readOnlyRequired && capability.descriptor.mutation) {
            appendAssistant(source.conversationId, source, selectedObjective?.objectiveId, "That capability is not available for this speech act.")
            return ConversationOperationResult(ConversationOperationStatus.REJECTED, projection(source.conversationId))
        }
        if (capability.descriptor.allowedObjectiveStates.isNotEmpty() &&
            selectedObjective?.state !in capability.descriptor.allowedObjectiveStates) {
            appendAssistant(source.conversationId, source, selectedObjective?.objectiveId, "Select one objective in an allowed state for $capabilityId.")
            return ConversationOperationResult(ConversationOperationStatus.AMBIGUOUS_OBJECTIVE, projection(source.conversationId))
        }
        val payloadJson = interpretation.payloadJson?.takeIf(String::isNotBlank) ?: "{}"
        capability.preflight(payloadJson, selectedObjective)?.let { existing ->
            appendAssistant(source.conversationId, source, selectedObjective?.objectiveId, existing.summary)
            appendActivity(
                source.conversationId,
                selectedObjective?.objectiveId,
                if (existing.success) ACTIVITY_INFO else ACTIVITY_ATTENTION,
                existing.summary,
                existing.downstreamType,
                existing.downstreamId,
                existing.downstreamHash,
            )
            return ConversationOperationResult(ConversationOperationStatus.RECORDED, projection(source.conversationId))
        }
        val command = synchronized(authorityLock) {
            val events = store.events()
            newConversationCommand(ConversationCommandProposal(
                commandId = nextCommandId(events),
                conversationId = source.conversationId,
                objectiveId = selectedObjective?.objectiveId,
                sourceMessageId = source.messageId,
                sourceMessageHash = source.hash,
                capabilityId = capabilityId,
                payloadJson = payloadJson,
                mutation = capability.descriptor.mutation,
                proposedAt = now(),
                hash = "",
            )).also(store::appendCommand)
        }
        if (command.mutation) {
            appendAssistant(source.conversationId, source, selectedObjective?.objectiveId, interpretation.response.ifBlank {
                "Command ${command.commandId} is awaiting admission with hash ${command.hash}."
            })
            return ConversationOperationResult(ConversationOperationStatus.ADMISSION_REQUIRED, projection(source.conversationId))
        }
        return execute(command)
    }

    private suspend fun execute(command: ConversationCommandProposal): ConversationOperationResult {
        val capability = capabilities.resolve(command.capabilityId)
            ?: return ConversationOperationResult(ConversationOperationStatus.REJECTED, projection(command.conversationId))
        val lock = objectiveLocks.computeIfAbsent(command.objectiveId ?: -command.commandId) { Mutex() }
        return lock.withLock {
            val current = synchronized(authorityLock) { store.events() }
            val terminal = current.mapNotNull { it.execution }.filter { it.commandId == command.commandId }
                .maxByOrNull { it.executionId }
            if (terminal?.state == COMMAND_CORRELATED) {
                return@withLock ConversationOperationResult(ConversationOperationStatus.ALREADY_RECORDED, projection(command.conversationId))
            }
            if (command.mutation && current.none { it.admission?.commandId == command.commandId }) {
                return@withLock ConversationOperationResult(ConversationOperationStatus.ADMISSION_REQUIRED, projection(command.conversationId))
            }
            val objective = synchronized(authorityLock) {
                latestObjectives(store.events(), command.conversationId).singleOrNull { it.objectiveId == command.objectiveId }
            }
            if (capability.descriptor.allowedObjectiveStates.isNotEmpty() &&
                (objective == null || objective.state !in capability.descriptor.allowedObjectiveStates)) {
                return@withLock ConversationOperationResult(
                    ConversationOperationStatus.REJECTED,
                    projection(command.conversationId),
                    "Objective state changed before ${command.capabilityId} could dispatch.",
                )
            }
            if (command.mutation && objective != null) {
                val objectives = synchronized(authorityLock) { latestObjectives(store.events(), command.conversationId).associateBy { it.objectiveId } }
                if (objective.dependencyObjectiveIds.any { objectives[it]?.state != OBJECTIVE_COMPLETED }) {
                    return@withLock ConversationOperationResult(
                        ConversationOperationStatus.REJECTED,
                        projection(command.conversationId),
                        "Objective dependencies are not complete.",
                    )
                }
            }
            val adopted = if (terminal?.state == COMMAND_DISPATCHED) capability.reconcile(command, objective) else null
            if (adopted != null) {
                synchronized(authorityLock) {
                    appendExecutionLocked(command, COMMAND_CORRELATED, adopted)
                    val source = store.events().mapNotNull { it.message }.single { it.messageId == command.sourceMessageId }
                    appendAssistantLocked(source, command.objectiveId, "Recovered after restart. ${adopted.summary}")
                }
                appendActivity(command.conversationId, command.objectiveId, ACTIVITY_INFO, adopted.summary,
                    adopted.downstreamType, adopted.downstreamId, adopted.downstreamHash)
                return@withLock ConversationOperationResult(ConversationOperationStatus.RECORDED, projection(command.conversationId))
            }
            synchronized(authorityLock) { appendExecutionLocked(command, COMMAND_DISPATCHED) }
            val result = runCatching { capability.execute(command.payloadJson, objective, command) }.getOrElse { error ->
                synchronized(authorityLock) { appendExecutionLocked(command, COMMAND_FAILED, diagnostic = error.message.orEmpty()) }
                appendActivity(command.conversationId, command.objectiveId, ACTIVITY_ATTENTION,
                    "${command.capabilityId} failed: ${error.message.orEmpty()}", "CONVERSATION_COMMAND", command.commandId.toString(), command.hash)
                return@withLock ConversationOperationResult(ConversationOperationStatus.REJECTED, projection(command.conversationId), error.message.orEmpty())
            }
            if (!result.success) {
                synchronized(authorityLock) { appendExecutionLocked(command, COMMAND_FAILED, diagnostic = result.summary) }
                appendActivity(command.conversationId, command.objectiveId, ACTIVITY_ATTENTION, result.summary,
                    result.downstreamType, result.downstreamId, result.downstreamHash)
                return@withLock ConversationOperationResult(ConversationOperationStatus.REJECTED, projection(command.conversationId), result.summary)
            }
            synchronized(authorityLock) {
                appendExecutionLocked(command, COMMAND_CORRELATED, result)
                val source = store.events().mapNotNull { it.message }.single { it.messageId == command.sourceMessageId }
                appendAssistantLocked(source, command.objectiveId, result.summary)
            }
            appendActivity(command.conversationId, command.objectiveId, ACTIVITY_INFO, result.summary,
                result.downstreamType, result.downstreamId, result.downstreamHash)
            ConversationOperationResult(ConversationOperationStatus.RECORDED, projection(command.conversationId))
        }
    }

    private fun appendAssistant(conversationId: Long, source: ConversationMessage, objectiveId: Long?, content: String) =
        synchronized(authorityLock) { appendAssistantLocked(source, objectiveId, content) }

    private fun appendAssistantLocked(source: ConversationMessage, objectiveId: Long?, content: String) {
        val events = store.events()
        val message = newConversationMessage(ConversationMessage(
            nextMessageId(events), source.conversationId,
            events.count { it.message?.conversationId == source.conversationId } + 1L,
            "assistant:${source.messageId}:${nextMessageId(events)}", MESSAGE_ROLE_ASSISTANT,
            content.ifBlank { "No additional detail was produced." }, objectiveId, source.messageId,
            "ORCHARD", now(), "",
        ))
        store.appendMessage(message)
    }

    private fun appendActivity(
        conversationId: Long,
        objectiveId: Long?,
        kind: String,
        summary: String,
        authorityType: String?,
        authorityId: String?,
        authorityHash: String?,
        modelProvenance: ConversationModelProvenance? = null,
    ) = synchronized(authorityLock) {
        val events = store.events()
        store.appendActivity(newConversationActivity(ConversationActivity(
            nextActivityId(events), conversationId, objectiveId, kind, summary,
            authorityType, authorityId, authorityHash, now(), "", modelProvenance,
        )))
    }

    private fun appendExecutionLocked(
        command: ConversationCommandProposal,
        state: String,
        result: ConversationCapabilityResult? = null,
        diagnostic: String = "",
    ) {
        val events = store.events()
        store.appendExecution(newConversationExecution(ConversationCommandExecution(
            nextExecutionId(events), command.commandId, command.hash, state,
            result?.downstreamType, result?.downstreamId, result?.downstreamHash, result?.repositoryRevision,
            diagnostic, now(), "",
        )))
    }

    private fun appendRollingSummaryLocked(conversationId: Long) {
        val events = store.events()
        val summarizedIds = events.mapNotNull { it.summary }.filter { it.conversationId == conversationId }
            .flatMapTo(mutableSetOf()) { it.sourceMessageIds }
        val unsummarized = events.mapNotNull { it.message }
            .filter { it.conversationId == conversationId && it.messageId !in summarizedIds }
        if (unsummarized.size < SUMMARY_BATCH_SIZE) return
        val source = unsummarized.take(SUMMARY_BATCH_SIZE)
        val content = source.joinToString("\n") { message ->
            "message:${message.messageId} role:${message.role} objective:${message.objectiveId ?: "none"} ${message.content.take(SUMMARY_CONTENT_CHARS)}"
        }
        val summary = newConversationSummary(ConversationSummaryRevision(
            summaryId = events.mapNotNull { it.summary }.maxOfOrNull { it.summaryId }?.plus(1) ?: 1,
            revision = 1,
            conversationId = conversationId,
            content = content,
            sourceMessageIds = source.map { it.messageId },
            createdAt = now(),
            hash = "",
        ))
        store.appendSummary(summary)
    }

    private fun resolveObjective(
        events: List<ConversationEvent>,
        conversationId: Long,
        explicitObjectiveId: Long?,
    ): ConversationObjectiveRevision? {
        val objectives = latestObjectives(events, conversationId)
        if (explicitObjectiveId != null) return objectives.singleOrNull { it.objectiveId == explicitObjectiveId }
        return objectives.filter { it.state in setOf(OBJECTIVE_READY, OBJECTIVE_ACTIVE, OBJECTIVE_BLOCKED, OBJECTIVE_PAUSED) }
            .singleOrNull()
    }

    private fun controlledObjective(
        prior: ConversationObjectiveRevision,
        request: ControlConversationObjectiveRequest,
    ): ConversationObjectiveRevision? = when (request.action) {
        OBJECTIVE_CONTROL_ADMIT -> prior.takeIf { it.state == OBJECTIVE_AWAITING_ADMISSION }?.copy(state = OBJECTIVE_READY)
        OBJECTIVE_CONTROL_PAUSE -> prior.takeIf { it.state in setOf(OBJECTIVE_READY, OBJECTIVE_ACTIVE, OBJECTIVE_BLOCKED) }?.copy(state = OBJECTIVE_PAUSED)
        OBJECTIVE_CONTROL_RESUME -> prior.takeIf { it.state == OBJECTIVE_PAUSED }?.copy(state = OBJECTIVE_READY)
        OBJECTIVE_CONTROL_CANCEL -> prior.takeIf { it.state !in setOf(OBJECTIVE_COMPLETED, OBJECTIVE_CANCELLED, OBJECTIVE_SUPERSEDED) }?.copy(state = OBJECTIVE_CANCELLED)
        OBJECTIVE_CONTROL_COMPLETE -> prior.takeIf { it.state in setOf(OBJECTIVE_READY, OBJECTIVE_ACTIVE, OBJECTIVE_BLOCKED) }?.copy(state = OBJECTIVE_COMPLETED)
        OBJECTIVE_CONTROL_SET_PRIORITY -> request.priority?.takeIf { it in 0..100 }?.let { prior.copy(priority = it) }
        OBJECTIVE_CONTROL_SET_DEPENDENCIES -> request.dependencyObjectiveIds?.let { prior.copy(dependencyObjectiveIds = it) }
        else -> null
    }
}

private fun project(events: List<ConversationEvent>, conversationId: Long, afterEventId: Long = 0): ConversationProjection? {
    val conversation = events.mapNotNull { it.conversation }.singleOrNull { it.conversationId == conversationId } ?: return null
    val commands = events.mapNotNull { it.command }.filter { it.conversationId == conversationId }
    val admissions = events.mapNotNull { it.admission }.associateBy { it.commandId }
    val executions = events.mapNotNull { it.execution }.groupBy { it.commandId }
    return ConversationProjection(
        conversation,
        events.mapNotNull { it.message }.filter { it.conversationId == conversationId },
        latestObjectives(events, conversationId),
        commands.map { ConversationCommandView(it, admissions[it.commandId], executions[it.commandId].orEmpty()) },
        events.mapNotNull { it.summary }.filter { it.conversationId == conversationId },
        events.mapNotNull { it.activity }.filter { it.conversationId == conversationId },
        events.filter { it.eventId > afterEventId && eventConversationId(it, commands) == conversationId },
        events.lastOrNull()?.eventId ?: 0,
    )
}

private fun eventConversationId(event: ConversationEvent, commands: List<ConversationCommandProposal>): Long? =
    event.conversation?.conversationId ?: event.message?.conversationId ?: event.objective?.conversationId ?:
    event.command?.conversationId ?: event.summary?.conversationId ?: event.activity?.conversationId ?:
    event.admission?.commandId?.let { id -> commands.singleOrNull { it.commandId == id }?.conversationId } ?:
    event.execution?.commandId?.let { id -> commands.singleOrNull { it.commandId == id }?.conversationId }

private fun latestObjectives(events: List<ConversationEvent>, conversationId: Long? = null): List<ConversationObjectiveRevision> =
    events.mapNotNull { it.objective }.filter { conversationId == null || it.conversationId == conversationId }
        .groupBy { it.objectiveId }.map { (_, revisions) -> revisions.maxBy { it.revision } }.sortedBy { it.objectiveId }

    private val executableStates = setOf(OBJECTIVE_READY, OBJECTIVE_ACTIVE, OBJECTIVE_BLOCKED)

private fun nextMessageId(events: List<ConversationEvent>) = events.mapNotNull { it.message }.maxOfOrNull { it.messageId }?.plus(1) ?: 1
private fun nextObjectiveId(events: List<ConversationEvent>) = events.mapNotNull { it.objective }.maxOfOrNull { it.objectiveId }?.plus(1) ?: 1
private fun nextCommandId(events: List<ConversationEvent>) = events.mapNotNull { it.command }.maxOfOrNull { it.commandId }?.plus(1) ?: 1
private fun nextAdmissionId(events: List<ConversationEvent>) = events.mapNotNull { it.admission }.maxOfOrNull { it.admissionId }?.plus(1) ?: 1
private fun nextExecutionId(events: List<ConversationEvent>) = events.mapNotNull { it.execution }.maxOfOrNull { it.executionId }?.plus(1) ?: 1
private fun nextActivityId(events: List<ConversationEvent>) = events.mapNotNull { it.activity }.maxOfOrNull { it.activityId }?.plus(1) ?: 1

fun conversationAuthorityHash(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

private const val SUMMARY_BATCH_SIZE = 24
private const val SUMMARY_CONTENT_CHARS = 160
private val ONBOARDING_ACTION = Regex("""\bonboard(?:ing)?\b""", RegexOption.IGNORE_CASE)
private val HTTP_REPOSITORY_SOURCE = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
private val ABSOLUTE_REPOSITORY_SOURCE = Regex("""(?:^|\s)/\S+""")