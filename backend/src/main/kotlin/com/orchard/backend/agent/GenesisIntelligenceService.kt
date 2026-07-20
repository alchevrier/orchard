package com.orchard.backend.agent

import com.orchard.backend.resource.MachineResourceController
import com.orchard.backend.resource.ResourceAdmissionDecision
import com.orchard.backend.vector.DefaultModelExecutionProfiles
import com.orchard.backend.vector.ModelProvider
import com.orchard.backend.vector.estimateModelTokens
import com.orchard.backend.workspace.GENESIS_ADMISSION
import com.orchard.backend.workspace.GENESIS_ARCHITECTURE
import com.orchard.backend.workspace.GENESIS_BLUEPRINT
import com.orchard.backend.workspace.GENESIS_CLASSIFICATION
import com.orchard.backend.workspace.GENESIS_READY
import com.orchard.backend.workspace.ArchitectureComponent
import com.orchard.backend.workspace.ArchitectureDecision
import com.orchard.backend.workspace.ENTITY_EPIC
import com.orchard.backend.workspace.ExperienceContract
import com.orchard.backend.workspace.ProjectGenesisSubmission
import com.orchard.backend.workspace.ProjectGenesisView
import com.orchard.backend.workspace.RepositoryBlueprint
import com.orchard.backend.workspace.WorkspaceStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

enum class GenesisProposalStatus {
    CREATED,
    PROJECT_NOT_FOUND,
    PHASE_NOT_PROPOSABLE,
    BUSY,
    MODEL_UNAVAILABLE,
    INVALID_OUTPUT,
    CONTEXT_BUDGET_EXCEEDED,
    RESOURCE_CAPACITY_UNAVAILABLE,
    RESOURCE_TELEMETRY_UNAVAILABLE,
}

@Serializable
data class GenesisProposalRequest(val prompt: String)

@Serializable
data class GenesisProposal(
    val projectId: Int,
    val phase: String,
    val baseRevision: Int,
    val baseHash: String? = null,
    val submission: ProjectGenesisSubmission,
    val observations: List<String> = emptyList(),
    val unresolvedQuestions: List<String> = emptyList(),
    val model: String,
)

data class GenesisProposalResult(
    val status: GenesisProposalStatus,
    val proposal: GenesisProposal? = null,
    val diagnostic: String = genesisProposalDiagnostic(status),
)

internal fun genesisProposalDiagnostic(status: GenesisProposalStatus): String = when (status) {
    GenesisProposalStatus.CREATED -> ""
    GenesisProposalStatus.PROJECT_NOT_FOUND -> "The selected project no longer exists. Refresh the conversation and retry."
    GenesisProposalStatus.PHASE_NOT_PROPOSABLE -> "This project phase no longer accepts Architect proposals. Refresh to load its current state."
    GenesisProposalStatus.BUSY -> "The Architect is already forming another proposal. Wait for it to finish, then retry."
    GenesisProposalStatus.MODEL_UNAVAILABLE -> "The configured Architect model is unavailable. Check the model provider, then retry."
    GenesisProposalStatus.INVALID_OUTPUT -> "The Architect returned a candidate that did not match the required phase schema. Revise the description and retry."
    GenesisProposalStatus.CONTEXT_BUDGET_EXCEEDED -> "The project context exceeds the Architect model's input budget. Shorten the description or select a larger model profile."
    GenesisProposalStatus.RESOURCE_CAPACITY_UNAVAILABLE -> "The Architect cannot run within the current machine resource policy. Retry when capacity is available."
    GenesisProposalStatus.RESOURCE_TELEMETRY_UNAVAILABLE -> "Machine telemetry is unavailable, so Orchard cannot safely admit the Architect model. Restore telemetry and retry."
}

class GenesisIntelligenceService(
    private val workspace: WorkspaceStore,
    private val modelProvider: ModelProvider,
    private val resourceController: MachineResourceController = MachineResourceController.unrestricted(),
    private val json: Json = Json { encodeDefaults = true },
    private val systemPrompt: String = loadPrompt(),
) {
    private val mutex = Mutex()
    private val strictJson = Json { encodeDefaults = true }

    suspend fun propose(projectId: Int, request: GenesisProposalRequest): GenesisProposalResult {
        if (!mutex.tryLock()) return GenesisProposalResult(GenesisProposalStatus.BUSY)
        return try {
            generate(projectId, request)
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun generate(projectId: Int, request: GenesisProposalRequest): GenesisProposalResult {
        if (request.prompt.isBlank() || request.prompt.encodeToByteArray().size > MAX_PROMPT_BYTES) {
            return GenesisProposalResult(GenesisProposalStatus.INVALID_OUTPUT)
        }
        val genesis = workspace.snapshot(0).projectGenesis.singleOrNull { it.projectId == projectId }
            ?: return GenesisProposalResult(GenesisProposalStatus.PROJECT_NOT_FOUND)
        if (genesis.phase in setOf(GENESIS_ADMISSION, GENESIS_READY)) {
            return GenesisProposalResult(GenesisProposalStatus.PHASE_NOT_PROPOSABLE)
        }
        val availableFirstEpics = (0 until workspace.entityCount).map(workspace::entityAt)
            .filter { it.type == ENTITY_EPIC && it.parentId == projectId }
            .map { GenesisEpicOption(it.id, it.title) }
        if (genesis.phase == GENESIS_ARCHITECTURE && availableFirstEpics.isEmpty()) {
            return GenesisProposalResult(
                GenesisProposalStatus.INVALID_OUTPUT,
                diagnostic = "Create and approve the first working outcome before forming an architecture proposal.",
            )
        }
        val envelope = GenesisProposalEnvelope(
            current = genesis,
            userMessage = request.prompt.trim(),
            requiredSubmissionShape = requiredShape(genesis.phase),
            requiredOutputExample = requiredOutputExample(genesis, availableFirstEpics.firstOrNull()?.epicId),
            availableFirstEpics = availableFirstEpics,
        )
        val envelopeJson = json.encodeToString(envelope)
        val prompt = "$systemPrompt\n\nAuthoritative genesis envelope:\n$envelopeJson"
        val profile = DefaultModelExecutionProfiles.boundedDefinitionReasoning
        if (estimateModelTokens(prompt) > profile.inputBudgetTokens) {
            return GenesisProposalResult(GenesisProposalStatus.CONTEXT_BUDGET_EXCEEDED)
        }
        val admission = resourceController.tryAcquire(modelProvider.resourceDemand(profile))
        val lease = admission.lease ?: return GenesisProposalResult(
            if (admission.evidence.decision == ResourceAdmissionDecision.TELEMETRY_UNAVAILABLE) {
                GenesisProposalStatus.RESOURCE_TELEMETRY_UNAVAILABLE
            } else {
                GenesisProposalStatus.RESOURCE_CAPACITY_UNAVAILABLE
            }
        )
        val generation = try {
            lease.use {
                modelProvider.executeWorkDefinition(
                    prompt,
                    profile.outputBudgetTokens,
                    profile.inputBudgetTokens + profile.outputBudgetTokens,
                )
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            return GenesisProposalResult(GenesisProposalStatus.MODEL_UNAVAILABLE)
        }
        if (
            generation.promptTokens > profile.inputBudgetTokens ||
            generation.completionTokens > profile.outputBudgetTokens
        ) return GenesisProposalResult(GenesisProposalStatus.INVALID_OUTPUT)
        val decoded = decodeProposalOutput(generation.text)
        val output = decoded.output
            ?: return GenesisProposalResult(
                GenesisProposalStatus.INVALID_OUTPUT,
                diagnostic = decoded.diagnostic,
            )
        val pinned = output.submission.copy(
            baseRevision = genesis.revision?.revision ?: 0,
            baseHash = genesis.revision?.hash,
        )
        if (!matchesPhase(genesis.phase, pinned)) {
            return GenesisProposalResult(
                GenesisProposalStatus.INVALID_OUTPUT,
                diagnostic = "The Architect returned fields outside the ${genesis.phase.lowercase()} phase. Revise the description and retry.",
            )
        }
        return GenesisProposalResult(
            GenesisProposalStatus.CREATED,
            GenesisProposal(
                projectId = projectId,
                phase = genesis.phase,
                baseRevision = pinned.baseRevision,
                baseHash = pinned.baseHash,
                submission = pinned,
                observations = output.observations.take(MAX_LIST_ENTRIES),
                unresolvedQuestions = output.unresolvedQuestions.take(MAX_LIST_ENTRIES),
                model = modelProvider.bindingProfile().model,
            ),
        )
    }

    private fun matchesPhase(phase: String, submission: ProjectGenesisSubmission): Boolean = when (phase) {
        GENESIS_CLASSIFICATION -> submission.classification != null && submission.productIntent != null &&
            submission.experience == null && submission.components == null && submission.decisions == null &&
            submission.firstEpicId == null && submission.blueprint == null
        "EXPERIENCE" -> submission.classification == null && submission.experience != null &&
            submission.components == null && submission.decisions == null && submission.firstEpicId == null &&
            submission.blueprint == null
        "ARCHITECTURE" -> submission.classification == null && submission.productIntent == null &&
            submission.experience == null && submission.components != null && submission.decisions != null &&
            submission.firstEpicId != null && submission.blueprint == null
        GENESIS_BLUEPRINT -> submission.classification == null && submission.productIntent == null &&
            submission.experience == null && submission.components == null && submission.decisions == null &&
            submission.firstEpicId == null && submission.blueprint != null
        else -> false
    }

    private fun decodeProposalOutput(text: String): ProposalDecodeResult {
        val candidate = JSON_CODE_FENCE.matchEntire(text)?.groupValues?.get(1) ?: text
        val element = runCatching { strictJson.parseToJsonElement(candidate) }.getOrNull()
            ?: return ProposalDecodeResult(
                diagnostic = "The Architect returned malformed JSON. Revise the description and retry.",
            )
        val canonical = canonicalizeProposalRoot(element)
        val output = runCatching {
            strictJson.decodeFromJsonElement(GenesisProposalOutput.serializer(), canonical)
        }.getOrNull()
        return ProposalDecodeResult(
            output = output,
            diagnostic = if (output == null) {
                "The Architect returned JSON that did not match the required schema (${schemaFingerprint(element)}). " +
                    "Revise the description and retry."
            } else {
                ""
            },
        )
    }

    private fun canonicalizeProposalRoot(element: JsonElement): JsonElement {
        val root = element as? JsonObject ?: return element
        val submission = root["submission"] as? JsonObject ?: return element
        val misplaced = root.keys.intersect(SUBMISSION_FIELDS)
        if (misplaced.isEmpty()) return element
        if (root.keys.any { it !in PROPOSAL_FIELDS && it !in SUBMISSION_FIELDS }) return element
        val canonicalSubmission = submission.toMutableMap().apply {
            listOf("baseRevision", "baseHash").forEach { field ->
                if (field !in this && root[field] != null) this[field] = requireNotNull(root[field])
            }
        }
        return JsonObject(
            root.filterKeys { it !in SUBMISSION_FIELDS } + ("submission" to JsonObject(canonicalSubmission))
        )
    }

    private fun schemaFingerprint(element: JsonElement): String {
        val root = element as? JsonObject ?: return "root:${jsonType(element)}"
        val parts = mutableListOf("root:${objectFingerprint(root)}")
        val submission = root["submission"] as? JsonObject
        if (submission != null) {
            parts += "submission:${objectFingerprint(submission)}"
            listOf("experience", "blueprint").forEach { field ->
                (submission[field] as? JsonObject)?.let { parts += "$field:${objectFingerprint(it)}" }
            }
        }
        return parts.joinToString("; ").take(MAX_SCHEMA_DIAGNOSTIC_CHARS)
    }

    private fun objectFingerprint(value: JsonObject): String = value.entries
        .sortedBy { it.key }
        .joinToString(prefix = "{", postfix = "}") { (key, element) -> "$key:${jsonType(element)}" }

    private fun jsonType(element: JsonElement): String = when (element) {
        is JsonObject -> "object"
        is JsonArray -> "array"
        is JsonNull -> "null"
        is JsonPrimitive -> when {
            element.isString -> "string"
            element.booleanOrNull != null -> "boolean"
            else -> "number"
        }
    }

    private fun requiredShape(phase: String): String = when (phase) {
        GENESIS_CLASSIFICATION -> "classification and productIntent only"
        "EXPERIENCE" -> "experience only; include audience, promise, journey, principles, qualities, exclusions, accessibility"
        "ARCHITECTURE" -> "components, decisions, and firstEpicId only; IDs must correlate"
        GENESIS_BLUEPRINT -> "blueprint only; include root name, toolchain, modules, verification commands, policy pack IDs"
        else -> "no proposal"
    }

    private fun requiredOutputExample(genesis: ProjectGenesisView, firstEpicId: Int?): JsonElement {
        val baseRevision = genesis.revision?.revision ?: 0
        val baseHash = genesis.revision?.hash
        val submission = when (genesis.phase) {
            GENESIS_CLASSIFICATION -> ProjectGenesisSubmission(
                classification = "EXISTING_LOCAL",
                productIntent = "The concrete outcome this project should create.",
                baseRevision = baseRevision,
                baseHash = baseHash,
            )
            "EXPERIENCE" -> ProjectGenesisSubmission(
                experience = ExperienceContract(
                    audience = "The people who use this product.",
                    productPromise = "The outcome they can reliably achieve.",
                    primaryJourney = listOf("First meaningful step", "Successful outcome"),
                    interactionPrinciples = listOf("One concrete interaction principle"),
                    emotionalQualities = listOf("Calm"),
                    mustNotFeelLike = listOf("Confusing"),
                    accessibility = listOf("Keyboard accessible"),
                ),
                baseRevision = baseRevision,
                baseHash = baseHash,
            )
            GENESIS_ARCHITECTURE -> ProjectGenesisSubmission(
                components = listOf(ArchitectureComponent(
                    componentId = "core",
                    name = "Core component",
                    responsibility = "Deliver the first working outcome.",
                    repositoryPaths = listOf("src"),
                )),
                decisions = listOf(ArchitectureDecision(
                    decisionId = "ADR-GENESIS-1",
                    title = "Founding decision",
                    context = "The constraint that requires this decision.",
                    decision = "The concrete architectural choice.",
                    consequences = listOf("One resulting tradeoff."),
                    componentIds = listOf("core"),
                )),
                firstEpicId = requireNotNull(firstEpicId),
                baseRevision = baseRevision,
                baseHash = baseHash,
            )
            GENESIS_BLUEPRINT -> ProjectGenesisSubmission(
                blueprint = RepositoryBlueprint(
                    rootName = "project",
                    toolchain = "Detected project toolchain",
                    modules = listOf("app"),
                    verificationCommands = listOf("project test command"),
                ),
                baseRevision = baseRevision,
                baseHash = baseHash,
            )
            else -> ProjectGenesisSubmission(baseRevision = baseRevision, baseHash = baseHash)
        }
        return json.encodeToJsonElement(GenesisProposalOutput.serializer(), GenesisProposalOutput(
            submission = submission,
            observations = listOf("One observation grounded in the supplied context."),
            unresolvedQuestions = emptyList(),
        ))
    }

    private companion object {
        const val MAX_PROMPT_BYTES = 16_384
        const val MAX_LIST_ENTRIES = 16
        const val MAX_SCHEMA_DIAGNOSTIC_CHARS = 384
        val JSON_CODE_FENCE = Regex("""\s*```(?:json)?\s*(\{.*})\s*```\s*""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val PROPOSAL_FIELDS = setOf("submission", "observations", "unresolvedQuestions")
        val SUBMISSION_FIELDS = setOf(
            "classification",
            "productIntent",
            "experience",
            "components",
            "decisions",
            "firstEpicId",
            "blueprint",
            "baseRevision",
            "baseHash",
        )

        fun loadPrompt(): String {
            val stream = requireNotNull(
                GenesisIntelligenceService::class.java.getResourceAsStream(
                    "/default-system-prompts/genesis_architect_agent.md"
                )
            ) { "Missing genesis architect agent prompt" }
            return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
    }
}

private data class ProposalDecodeResult(
    val output: GenesisProposalOutput? = null,
    val diagnostic: String,
)

@Serializable
private data class GenesisProposalEnvelope(
    val envelopeVersion: Int = 1,
    val allowedAction: String = "PROPOSE_CURRENT_GENESIS_TRANSITION",
    val forbiddenActions: List<String> = listOf(
        "ADMIT_GENESIS",
        "START_IMPLEMENTATION",
        "CREATE_REPOSITORY",
        "CHANGE_PRIOR_AUTHORITY",
    ),
    val current: ProjectGenesisView,
    val userMessage: String,
    val requiredSubmissionShape: String,
    val requiredOutputExample: JsonElement,
    val availableFirstEpics: List<GenesisEpicOption>,
)

@Serializable
private data class GenesisEpicOption(val epicId: Int, val title: String)

@Serializable
private data class GenesisProposalOutput(
    val submission: ProjectGenesisSubmission,
    val observations: List<String> = emptyList(),
    val unresolvedQuestions: List<String> = emptyList(),
)