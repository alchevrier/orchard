package com.orchard.backend.conversation

import com.orchard.backend.api.DocumentIntent
import com.orchard.backend.agent.CircuitGenerationStatus
import com.orchard.backend.agent.CircuitIntelligenceService
import com.orchard.backend.agent.DefinitionIntelligenceService
import com.orchard.backend.agent.ProposalGenerationStatus
import com.orchard.backend.company.CompanyCircuitService
import com.orchard.backend.company.CompanyCircuitStatus
import com.orchard.backend.company.CompanyControlService
import com.orchard.backend.company.CompanyMutationStatus
import com.orchard.backend.vector.CatalogModelBinding
import com.orchard.backend.vector.ModelEndpointDefinition
import com.orchard.backend.vector.ModelEndpointInspection
import com.orchard.backend.vector.ModelProfileConfiguration
import com.orchard.backend.vector.ModelProfileOverride
import com.orchard.backend.vector.ModelProfileUpdateStatus
import com.orchard.backend.vector.ModelProviderCatalog
import com.orchard.backend.vector.ModelProviderRegistry
import com.orchard.backend.workspace.MESSAGE_READY
import com.orchard.backend.workspace.ACTION_CREATE
import com.orchard.backend.workspace.DEFAULT_DELIVERY_WORKFLOW_ID
import com.orchard.backend.workspace.ENTITY_EPIC
import com.orchard.backend.workspace.GENESIS_READY
import com.orchard.backend.workspace.ProjectGenesisStatus
import com.orchard.backend.workspace.ProjectGenesisSubmission
import com.orchard.backend.workspace.RepositoryBindStatus
import com.orchard.backend.workspace.RepositoryOnboardingRequest
import com.orchard.backend.workspace.RepositoryOnboardingService
import com.orchard.backend.workspace.RepositoryOnboardingStatus
import com.orchard.backend.workspace.StagedPlanStatus
import com.orchard.backend.workspace.WorkflowMutationStatus
import com.orchard.backend.workspace.WorkflowStartStatus
import com.orchard.backend.workspace.WorkDefinitionStatus
import com.orchard.backend.workspace.WorkspaceStore
import com.orchard.backend.workspace.ConversationCommandReference
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Path

const val CAPABILITY_REQUEST_STATUS = "REQUEST_STATUS"
const val CAPABILITY_INSPECT_REPOSITORY = "INSPECT_REPOSITORY"
const val CAPABILITY_BIND_REPOSITORY = "BIND_REPOSITORY"
const val CAPABILITY_CREATE_WORK_ITEM = "CREATE_WORK_ITEM"
const val CAPABILITY_GENERATE_WORK_DEFINITION = "GENERATE_WORK_DEFINITION"
const val CAPABILITY_ACCEPT_WORK_DEFINITION = "ACCEPT_WORK_DEFINITION"
const val CAPABILITY_GENERATE_STAGED_PLAN = "GENERATE_STAGED_PLAN"
const val CAPABILITY_ACCEPT_STAGED_PLAN = "ACCEPT_STAGED_PLAN"
const val CAPABILITY_START_WORKFLOW = "START_WORKFLOW"
const val CAPABILITY_CANCEL_WORKFLOW = "CANCEL_WORKFLOW"
const val CAPABILITY_START_COMPANY = "START_COMPANY"
const val CAPABILITY_PROMOTE_RUN = "PROMOTE_RUN"
const val CAPABILITY_INSPECT_PROJECT_GENESIS = "INSPECT_PROJECT_GENESIS"
const val CAPABILITY_ADVANCE_PROJECT_GENESIS = "ADVANCE_PROJECT_GENESIS"
const val CAPABILITY_ADMIT_PROJECT_GENESIS = "ADMIT_PROJECT_GENESIS"
const val CAPABILITY_ONBOARD_REPOSITORY = "ONBOARD_REPOSITORY"
const val CAPABILITY_INSPECT_MODEL_CONFIGURATION = "INSPECT_MODEL_CONFIGURATION"
const val CAPABILITY_REGISTER_MODEL = "REGISTER_MODEL"
const val CAPABILITY_ASSIGN_MODEL_PROFILE = "ASSIGN_MODEL_PROFILE"

@Serializable data class ProjectCapabilityPayload(val projectId: Int)
@Serializable data class RepositoryCapabilityPayload(val projectId: Int, val path: String)
@Serializable data class WorkItemCapabilityPayload(val workItemId: Int)
@Serializable data class ScopeCapabilityPayload(val scopeId: Int)
@Serializable data class ProposalCapabilityPayload(val proposalId: Long)
@Serializable data class RunCapabilityPayload(val runId: Long)
@Serializable data class CreateWorkItemCapabilityPayload(
    val entityTypeId: Int,
    val projectId: Int = 0,
    val epicId: Int = 0,
    val storyId: Int = 0,
    val title: String,
    val content: String = "",
)
@Serializable data class AdvanceProjectGenesisCapabilityPayload(
    val projectId: Int,
    val submission: ProjectGenesisSubmission,
)
@Serializable data class OnboardRepositoryCapabilityPayload(
    val source: String,
    val location: String,
    val projectTitle: String,
    val projectId: Int = 0,
)
@Serializable data class RegisterModelCapabilityPayload(
    val endpoint: ModelEndpointDefinition,
    val binding: CatalogModelBinding,
    val providerPolicy: String? = null,
)
@Serializable data class AssignModelProfileCapabilityPayload(
    val profileId: String,
    val preferredBindingId: String,
    val inputBudgetTokens: Int? = null,
    val outputBudgetTokens: Int? = null,
)
@Serializable data class ModelConfigurationInspection(
    val catalog: ModelProviderCatalog,
    val profiles: List<ModelProfileConfiguration>,
    val endpoints: List<ModelEndpointInspection>,
)

fun defaultConversationCapabilities(
    workspace: WorkspaceStore,
    definitionIntelligence: DefinitionIntelligenceService? = null,
    circuitIntelligence: CircuitIntelligenceService? = null,
    companyControl: CompanyControlService? = null,
    companyCircuit: CompanyCircuitService? = null,
    repositoryOnboarding: RepositoryOnboardingService? = null,
    modelProviderRegistry: ModelProviderRegistry? = null,
    json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = false },
): ConversationCapabilityRegistry = ConversationCapabilityRegistry(listOfNotNull(
    object : ConversationCapability {
        override val descriptor = descriptor(
            CAPABILITY_REQUEST_STATUS,
            "Report current workspace and objective authority without mutation.",
            mutation = false,
            payloadSchema = "{}",
            owningService = "WorkspaceStore",
            resultType = "WORKSPACE_SNAPSHOT",
            idempotencyStrategy = "READ_ONLY",
        )

        override suspend fun execute(payloadJson: String, objective: ConversationObjectiveRevision?, command: ConversationCommandProposal): ConversationCapabilityResult {
            requireEmptyPayload(payloadJson, json)
            val snapshot = workspace.snapshot(MESSAGE_READY)
            val encoded = json.encodeToString(snapshot)
            val objectiveSummary = objective?.let { " Objective ${it.objectiveId} is ${it.state}." }.orEmpty()
            return success(
                "Workspace has ${snapshot.workflowRuns.size} workflow runs, ${snapshot.workDefinitions.size} definitions, " +
                    "${snapshot.stagedPlans.size} staged plans, and ${snapshot.repositories.size} repositories.$objectiveSummary",
                "WORKSPACE_SNAPSHOT",
                objective?.projectId?.toString() ?: "workspace",
                encoded,
            )
        }
    },
    repositoryOnboarding?.let { service ->
        object : ConversationCapability {
            override val descriptor = descriptor(
                CAPABILITY_ONBOARD_REPOSITORY,
                "Create or select a project and bind either an existing local Git folder or a safely cloned HTTP(S) Git repository. Cloning never executes repository code, submodules, or LFS smudging.",
                mutation = true,
                payloadSchema = """{"source":"LOCAL_FOLDER|GIT_URL","location":"absolute folder or HTTP(S) Git URL without credentials","projectTitle":"title required for a new project","projectId":"existing project ID or 0 to create"}""",
                owningService = "RepositoryOnboardingService",
                resultType = "REPOSITORY_ONBOARDING",
                idempotencyStrategy = "COMMAND_REFERENCE_AND_CANONICAL_SOURCE",
            )

            override suspend fun reconcile(
                command: ConversationCommandProposal,
                objective: ConversationObjectiveRevision?,
            ): ConversationCapabilityResult? {
                val project = workspace.entities().singleOrNull { it.conversationCommand == commandReference(command) }
                    ?: return null
                val repository = workspace.snapshot(MESSAGE_READY).repositories[project.id] ?: return null
                return success(
                    "Recovered repository onboarding for project ${project.id} at ${repository.path}.",
                    "REPOSITORY_ONBOARDING",
                    project.id.toString(),
                    json.encodeToString(repository),
                )
            }

            override suspend fun preflight(
                payloadJson: String,
                objective: ConversationObjectiveRevision?,
            ): ConversationCapabilityResult? {
                val payload = runCatching { json.decodeFromString<OnboardRepositoryCapabilityPayload>(payloadJson) }.getOrNull()
                    ?: return null
                val existing = service.findExisting(
                    RepositoryOnboardingRequest(payload.source, payload.location, payload.projectTitle, payload.projectId)
                ) ?: return null
                val project = existing.project ?: return null
                val repository = existing.repository ?: return null
                return success(
                    "Repository is already onboarded as project ${project.id} (${project.title}) at ${repository.path} on ${repository.branch}. " +
                        "No new admission is needed. Next: inspect repository for project ${project.id}, then define an objective for the work you want Orchard to perform.",
                    "REPOSITORY_ONBOARDING",
                    project.id.toString(),
                    json.encodeToString(repository),
                )
            }

            override suspend fun execute(
                payloadJson: String,
                objective: ConversationObjectiveRevision?,
                command: ConversationCommandProposal,
            ): ConversationCapabilityResult {
                val payload = json.decodeFromString<OnboardRepositoryCapabilityPayload>(payloadJson)
                require(payload.location.isNotBlank() && payload.projectId >= 0)
                require(
                    objective?.projectId == null ||
                        (payload.projectId > 0 && objective.projectId == payload.projectId)
                ) { "Repository onboarding crossed the selected objective's project authority." }
                val result = service.onboard(
                    RepositoryOnboardingRequest(payload.source, payload.location, payload.projectTitle, payload.projectId),
                    commandReference(command),
                )
                if (result.status != RepositoryOnboardingStatus.ONBOARDED || result.project == null || result.repository == null) {
                    return failure(
                        "Repository was not onboarded: ${result.status}. ${result.diagnostic}",
                        "REPOSITORY_ONBOARDING",
                        payload.projectId.toString(),
                    )
                }
                return success(
                    "Onboarded project ${result.project.id} (${result.project.title}) from ${payload.source}; repository is pinned at ${result.repository.path} on ${result.repository.branch}. " +
                        "Next: inspect repository for project ${result.project.id}, then define an objective for the work you want Orchard to perform.",
                    "REPOSITORY_ONBOARDING",
                    result.project.id.toString(),
                    json.encodeToString(result.repository),
                )
            }
        }
    },
    modelProviderRegistry?.let { registry ->
        object : ConversationCapability {
            override val descriptor = descriptor(
                CAPABILITY_INSPECT_MODEL_CONFIGURATION,
                "Inspect installed model endpoints, bindings, provider policy, compatibility, and workload profile assignments.",
                mutation = false,
                payloadSchema = "{}",
                owningService = "ModelProviderRegistry",
                resultType = "MODEL_CONFIGURATION",
                idempotencyStrategy = "READ_ONLY",
            )

            override suspend fun execute(
                payloadJson: String,
                objective: ConversationObjectiveRevision?,
                command: ConversationCommandProposal,
            ): ConversationCapabilityResult {
                requireEmptyPayload(payloadJson, json)
                val catalog = registry.catalog()
                val profiles = definitionIntelligence?.profileConfigurations().orEmpty()
                val inspections = registry.inspect()
                val summary = buildString {
                    append("Model policy is ${catalog.policy}. Bindings: ")
                    append(catalog.bindings.joinToString { "${it.bindingId}=${it.model}" }.ifBlank { "none" })
                    append(". Profile assignments: ")
                    append(profiles.joinToString { configuration ->
                        "${configuration.defaultProfile.id}=${configuration.override?.preferredBindingId ?: "automatic"}"
                    }.ifBlank { "unavailable" })
                    append(". Endpoint health: ")
                    append(inspections.joinToString { "${it.endpointId}=${if (it.reachable) "reachable" else "unreachable"}" }.ifBlank { "none" })
                }
                return success(
                    summary,
                    "MODEL_CONFIGURATION",
                    "catalog",
                    json.encodeToString(ModelConfigurationInspection(catalog, profiles, inspections)),
                )
            }
        }
    },
    modelProviderRegistry?.let { registry ->
        object : ConversationCapability {
            override val descriptor = descriptor(
                CAPABILITY_REGISTER_MODEL,
                "Register or replace one model endpoint and binding. Secrets are accepted only as environment credential references and never as values.",
                mutation = true,
                payloadSchema = """{"endpoint":{"endpointId":"stable ID","displayName":"name","protocol":"OLLAMA_NATIVE|OPENAI_COMPATIBLE","baseUrl":"URL","locality":"LOCAL|REMOTE","credentialReference":"optional env:NAME","enabled":true},"binding":{"bindingId":"stable ID","endpointId":"matching endpoint ID","model":"model name","contextWindowTokens":integer,"capabilities":["STRICT_JSON"],"modelDigest":"optional","residentMemoryBytes":integer,"cpuUnits":integer,"configuration":{}},"providerPolicy":"optional LOCAL_ONLY|LOCAL_PREFERRED|CLOUD_ALLOWED|CLOUD_ESCALATION_ONLY"}""",
                owningService = "ModelProviderRegistry",
                resultType = "MODEL_BINDING",
                idempotencyStrategy = "ENDPOINT_AND_BINDING_ID",
            )

            override suspend fun reconcile(
                command: ConversationCommandProposal,
                objective: ConversationObjectiveRevision?,
            ): ConversationCapabilityResult? {
                val payload = json.decodeFromString<RegisterModelCapabilityPayload>(command.payloadJson)
                val catalog = registry.catalog()
                val reference = commandReference(command)
                val endpoint = catalog.endpoints.singleOrNull { it.endpointId == payload.endpoint.endpointId }
                val binding = catalog.bindings.singleOrNull { it.bindingId == payload.binding.bindingId }
                if (endpoint?.copy(conversationCommand = null) != payload.endpoint.copy(conversationCommand = null) ||
                    binding?.copy(conversationCommand = null) != payload.binding.copy(conversationCommand = null) ||
                    endpoint.conversationCommand != reference || binding.conversationCommand != reference ||
                    (payload.providerPolicy != null && catalog.policy != payload.providerPolicy)
                ) return null
                return success(
                    "Recovered model binding ${payload.binding.bindingId}.",
                    "MODEL_BINDING",
                    payload.binding.bindingId,
                    json.encodeToString(payload.binding),
                )
            }

            override suspend fun execute(
                payloadJson: String,
                objective: ConversationObjectiveRevision?,
                command: ConversationCommandProposal,
            ): ConversationCapabilityResult {
                val payload = json.decodeFromString<RegisterModelCapabilityPayload>(payloadJson)
                require(payload.binding.endpointId == payload.endpoint.endpointId) { "Model binding endpoint does not match the registered endpoint." }
                val current = registry.catalog()
                val reference = commandReference(command)
                val currentEndpoint = current.endpoints.singleOrNull { it.endpointId == payload.endpoint.endpointId }
                val currentBinding = current.bindings.singleOrNull { it.bindingId == payload.binding.bindingId }
                if (listOfNotNull(currentEndpoint?.conversationCommand, currentBinding?.conversationCommand)
                    .any { it.commandId > command.commandId }) {
                    return failure(
                        "Model registration was superseded by a newer admitted command.",
                        "MODEL_BINDING",
                        payload.binding.bindingId,
                    )
                }
                val admittedEndpoint = payload.endpoint.copy(conversationCommand = reference)
                val admittedBinding = payload.binding.copy(conversationCommand = reference)
                if (currentEndpoint == admittedEndpoint && currentBinding == admittedBinding &&
                    (payload.providerPolicy == null || current.policy == payload.providerPolicy)) {
                    return success(
                        "Model binding ${payload.binding.bindingId} is already registered by this command.",
                        "MODEL_BINDING",
                        payload.binding.bindingId,
                        json.encodeToString(admittedBinding),
                    )
                }
                val updated = ModelProviderCatalog(
                    policy = payload.providerPolicy ?: current.policy,
                    endpoints = current.endpoints.filterNot { it.endpointId == payload.endpoint.endpointId } + admittedEndpoint,
                    bindings = current.bindings.filterNot { it.bindingId == payload.binding.bindingId } + admittedBinding,
                )
                registry.update(updated)
                return success(
                    "Registered ${payload.binding.model} as ${payload.binding.bindingId} on ${payload.endpoint.displayName}.",
                    "MODEL_BINDING",
                    payload.binding.bindingId,
                    json.encodeToString(admittedBinding),
                )
            }
        }
    },
    if (modelProviderRegistry != null && definitionIntelligence != null) {
        object : ConversationCapability {
            override val descriptor = descriptor(
                CAPABILITY_ASSIGN_MODEL_PROFILE,
                "Assign one installed compatible model binding to a workload profile, optionally changing its token budgets.",
                mutation = true,
                payloadSchema = """{"profileId":"existing execution profile ID","preferredBindingId":"installed binding ID","inputBudgetTokens":"optional integer","outputBudgetTokens":"optional integer"}""",
                owningService = "DefinitionIntelligenceService",
                resultType = "MODEL_PROFILE_CONFIGURATION",
                idempotencyStrategy = "PROFILE_OVERRIDE",
            )

            override suspend fun reconcile(
                command: ConversationCommandProposal,
                objective: ConversationObjectiveRevision?,
            ): ConversationCapabilityResult? {
                val payload = json.decodeFromString<AssignModelProfileCapabilityPayload>(command.payloadJson)
                val configuration = definitionIntelligence.profileConfigurations()
                    .singleOrNull { it.defaultProfile.id == payload.profileId } ?: return null
                val override = configuration.override ?: return null
                if (override.conversationCommand != commandReference(command) ||
                    override.preferredBindingId != payload.preferredBindingId ||
                    (payload.inputBudgetTokens != null && override.inputBudgetTokens != payload.inputBudgetTokens) ||
                    (payload.outputBudgetTokens != null && override.outputBudgetTokens != payload.outputBudgetTokens)
                ) return null
                return success(
                    "Recovered model assignment ${payload.profileId} -> ${payload.preferredBindingId}.",
                    "MODEL_PROFILE_CONFIGURATION",
                    payload.profileId,
                    json.encodeToString(configuration),
                )
            }

            override suspend fun execute(
                payloadJson: String,
                objective: ConversationObjectiveRevision?,
                command: ConversationCommandProposal,
            ): ConversationCapabilityResult {
                val payload = json.decodeFromString<AssignModelProfileCapabilityPayload>(payloadJson)
                val current = definitionIntelligence.profileConfigurations()
                    .singleOrNull { it.defaultProfile.id == payload.profileId }
                    ?: return failure("Model profile ${payload.profileId} does not exist.", "MODEL_PROFILE_CONFIGURATION", payload.profileId)
                val currentOverride = current.override
                if (currentOverride?.conversationCommand?.commandId?.let { it > command.commandId } == true) {
                    return failure(
                        "Model profile assignment was superseded by a newer admitted command.",
                        "MODEL_PROFILE_CONFIGURATION",
                        payload.profileId,
                    )
                }
                val result = definitionIntelligence.updateProfile(ModelProfileOverride(
                    profileId = payload.profileId,
                    inputBudgetTokens = payload.inputBudgetTokens ?: current.effectiveProfile.inputBudgetTokens,
                    outputBudgetTokens = payload.outputBudgetTokens ?: current.effectiveProfile.outputBudgetTokens,
                    preferredBindingId = payload.preferredBindingId,
                    conversationCommand = commandReference(command),
                ))
                if (result.status != ModelProfileUpdateStatus.UPDATED) {
                    return failure("Model profile was not assigned: ${result.status}.", "MODEL_PROFILE_CONFIGURATION", payload.profileId)
                }
                val configuration = result.configurations.single { it.defaultProfile.id == payload.profileId }
                return success(
                    "Assigned ${payload.preferredBindingId} to ${payload.profileId} with ${configuration.effectiveProfile.inputBudgetTokens}/${configuration.effectiveProfile.outputBudgetTokens} input/output tokens.",
                    "MODEL_PROFILE_CONFIGURATION",
                    payload.profileId,
                    json.encodeToString(configuration),
                )
            }
        }
    } else null,
    object : ConversationCapability {
        override val descriptor = descriptor(
            CAPABILITY_CREATE_WORK_ITEM,
            "Create one typed project, epic, story, task, or bug through workspace hierarchy validation.",
            mutation = true,
            payloadSchema = "{\"entityTypeId\": 1..5, \"projectId\": integer, \"epicId\": integer, \"storyId\": integer, \"title\": string, \"content\": string}",
            states = executableStates,
            owningService = "WorkspaceStore",
            resultType = "WORKSPACE_ENTITY",
            idempotencyStrategy = "COMMAND_REFERENCE",
        )

        override suspend fun reconcile(
            command: ConversationCommandProposal,
            objective: ConversationObjectiveRevision?,
        ): ConversationCapabilityResult? = (0 until workspace.entityCount).map(workspace::entityAt)
            .singleOrNull { it.conversationCommand == commandReference(command) }
            ?.let { success("Recovered workspace entity ${it.id}: ${it.title}.", "WORKSPACE_ENTITY", it.id.toString(), json.encodeToString(it)) }

        override suspend fun execute(payloadJson: String, objective: ConversationObjectiveRevision?, command: ConversationCommandProposal): ConversationCapabilityResult {
            val payload = json.decodeFromString<CreateWorkItemCapabilityPayload>(payloadJson)
            require(payload.entityTypeId in 1..5 && payload.title.isNotBlank())
            require(objective?.projectId == null || payload.projectId == objective.projectId) {
                "Workspace creation crossed the selected objective's project authority."
            }
            val created = synchronized(workspace) {
                workspace.beginBatch()
                try {
                    val accepted = workspace.applyIntent(DocumentIntent(
                        ACTION_CREATE,
                        payload.entityTypeId,
                        DEFAULT_DELIVERY_WORKFLOW_ID,
                        payload.projectId,
                        payload.epicId,
                        payload.storyId,
                        payload.title.trim(),
                        payload.content.trim(),
                        commandReference(command),
                    ))
                    if (!accepted) {
                        workspace.rollbackBatch()
                        null
                    } else {
                        val createdId = workspace.lastCreatedId
                        workspace.commitBatch()
                        (0 until workspace.entityCount).map(workspace::entityAt).single { it.id == createdId }
                    }
                } catch (error: Exception) {
                    runCatching { workspace.rollbackBatch() }
                    throw error
                }
            } ?: return failure("Workspace hierarchy rejected the requested entity.", "WORKSPACE_ENTITY", "rejected")
            return success(
                "Created workspace entity ${created.id}: ${created.title}.",
                "WORKSPACE_ENTITY",
                created.id.toString(),
                json.encodeToString(created),
            )
        }
    },
    object : ConversationCapability {
        override val descriptor = descriptor(
            CAPABILITY_INSPECT_REPOSITORY,
            "Inspect one bound repository's current metadata.",
            mutation = false,
            payloadSchema = "{\"projectId\": positive integer}",
            owningService = "RepositoryBindingStore",
            resultType = "REPOSITORY",
            idempotencyStrategy = "READ_ONLY",
        )

        override suspend fun execute(payloadJson: String, objective: ConversationObjectiveRevision?, command: ConversationCommandProposal): ConversationCapabilityResult {
            val payload = json.decodeFromString<ProjectCapabilityPayload>(payloadJson)
            require(payload.projectId > 0 && (objective?.projectId == null || objective.projectId == payload.projectId)) {
                "Repository inspection crossed the selected objective's project authority."
            }
            val repository = workspace.snapshot(MESSAGE_READY).repositories[payload.projectId]
                ?: return failure("Project ${payload.projectId} has no bound repository.", "REPOSITORY", payload.projectId.toString())
            val encoded = json.encodeToString(repository)
            return success(
                "Repository ${repository.path} is ${if (repository.available) "available" else "unavailable"} on ${repository.branch.ifBlank { "an unknown branch" }}; dirty=${repository.dirty}.",
                "REPOSITORY",
                payload.projectId.toString(),
                encoded,
            )
        }
    },
    object : ConversationCapability {
        override val descriptor = descriptor(
            CAPABILITY_BIND_REPOSITORY,
            "Bind one project to an existing local Git repository using its canonical path.",
            mutation = true,
            payloadSchema = "{\"projectId\": positive integer, \"path\": absolute local repository path}",
            states = executableStates,
            owningService = "WorkspaceStore",
            resultType = "REPOSITORY",
            idempotencyStrategy = "PROJECT_CANONICAL_PATH",
        )

        override suspend fun reconcile(
            command: ConversationCommandProposal,
            objective: ConversationObjectiveRevision?,
        ): ConversationCapabilityResult? {
            val payload = json.decodeFromString<RepositoryCapabilityPayload>(command.payloadJson)
            val canonical = runCatching { Path.of(payload.path).toRealPath().toString() }.getOrNull() ?: return null
            return workspace.snapshot(MESSAGE_READY).repositories[payload.projectId]
                ?.takeIf { it.path == canonical }
                ?.let { success("Recovered repository binding for project ${payload.projectId}.", "REPOSITORY", payload.projectId.toString(), json.encodeToString(it)) }
        }

        override suspend fun execute(payloadJson: String, objective: ConversationObjectiveRevision?, command: ConversationCommandProposal): ConversationCapabilityResult {
            val payload = json.decodeFromString<RepositoryCapabilityPayload>(payloadJson)
            require(payload.projectId > 0 && payload.path.isNotBlank() &&
                (objective?.projectId == null || objective.projectId == payload.projectId)) {
                "Repository binding crossed the selected objective's project authority."
            }
            val result = workspace.bindRepository(payload.projectId, payload.path)
            if (result.status != RepositoryBindStatus.BOUND) {
                return failure("Repository was not bound: ${result.status}.", "REPOSITORY", payload.projectId.toString())
            }
            val repository = result.snapshot.repositories[payload.projectId]
                ?: return failure("The repository binding could not be resolved.", "REPOSITORY", payload.projectId.toString())
            return success(
                "Project ${payload.projectId} is bound to ${repository.path} on ${repository.branch}.",
                "REPOSITORY",
                payload.projectId.toString(),
                json.encodeToString(repository),
            )
        }
    },
    object : ConversationCapability {
        override val descriptor = descriptor(
            CAPABILITY_INSPECT_PROJECT_GENESIS,
            "Inspect one project's current guided genesis phase and admitted revision.",
            mutation = false,
            payloadSchema = "{\"projectId\": positive integer}",
            owningService = "WorkspaceStore",
            resultType = "PROJECT_GENESIS",
            idempotencyStrategy = "READ_ONLY",
        )

        override suspend fun execute(payloadJson: String, objective: ConversationObjectiveRevision?, command: ConversationCommandProposal): ConversationCapabilityResult {
            val payload = json.decodeFromString<ProjectCapabilityPayload>(payloadJson)
            require(payload.projectId > 0 && (objective?.projectId == null || objective.projectId == payload.projectId)) {
                "Genesis inspection crossed the selected objective's project authority."
            }
            val genesis = workspace.snapshot(MESSAGE_READY).projectGenesis.singleOrNull { it.projectId == payload.projectId }
                ?: return failure("Project ${payload.projectId} does not exist.", "PROJECT_GENESIS", payload.projectId.toString())
            val epics = (0 until workspace.entityCount).map(workspace::entityAt)
                .filter { it.type == ENTITY_EPIC && it.parentId == payload.projectId }
                .joinToString { "${it.id}:${it.title}" }.ifBlank { "none" }
            return success(
                "Project ${payload.projectId} genesis is ${genesis.phase} at revision ${genesis.revision?.revision ?: 0}. " +
                    "Next question: ${genesis.nextQuestion} Available epics: $epics.",
                "PROJECT_GENESIS",
                payload.projectId.toString(),
                json.encodeToString(genesis),
            )
        }
    },
    object : ConversationCapability {
        override val descriptor = descriptor(
            CAPABILITY_ADVANCE_PROJECT_GENESIS,
            "Advance one exact guided genesis phase using a revision-pinned submission.",
            mutation = true,
            payloadSchema = """{"projectId": positive integer, "submission": {"classification": optional string, "productIntent": optional string, "experience": optional {audience, productPromise, primaryJourney[], interactionPrinciples[], emotionalQualities[], mustNotFeelLike[], accessibility[]}, "components": optional [{componentId, name, responsibility, dependsOn[], requirementIds[], repositoryPaths[]}], "decisions": optional [{decisionId, title, status, context, decision, consequences[], componentIds[], requirementIds[]}], "firstEpicId": optional integer, "blueprint": optional {rootName, toolchain, modules[], verificationCommands[], policyPackIds[]}, "baseRevision": integer, "baseHash": optional SHA-256}}""",
            states = executableStates,
            owningService = "WorkspaceStore",
            resultType = "PROJECT_GENESIS_REVISION",
            idempotencyStrategy = "COMMAND_REFERENCE",
        )

        override suspend fun reconcile(
            command: ConversationCommandProposal,
            objective: ConversationObjectiveRevision?,
        ): ConversationCapabilityResult? = workspace.snapshot(MESSAGE_READY).projectGenesis
            .mapNotNull { it.revision }
            .singleOrNull { it.conversationCommand == commandReference(command) }
            ?.let { success("Recovered project genesis revision ${it.revision}.", "PROJECT_GENESIS_REVISION", it.genesisId.toString(), it.hash, alreadyHashed = true) }

        override suspend fun execute(payloadJson: String, objective: ConversationObjectiveRevision?, command: ConversationCommandProposal): ConversationCapabilityResult {
            val payload = json.decodeFromString<AdvanceProjectGenesisCapabilityPayload>(payloadJson)
            require(payload.projectId > 0 && (objective?.projectId == null || objective.projectId == payload.projectId)) {
                "Genesis mutation crossed the selected objective's project authority."
            }
            val result = workspace.advanceProjectGenesis(payload.projectId, payload.submission, commandReference(command))
            if (result.status != ProjectGenesisStatus.RECORDED) {
                return failure("Project genesis did not advance: ${result.status}.", "PROJECT_GENESIS", payload.projectId.toString())
            }
            val revision = result.snapshot.projectGenesis.single { it.projectId == payload.projectId }.revision
                ?: return failure("The recorded genesis revision could not be resolved.", "PROJECT_GENESIS", payload.projectId.toString())
            return success(
                "Project ${payload.projectId} genesis advanced to ${revision.phase} revision ${revision.revision}.",
                "PROJECT_GENESIS_REVISION",
                revision.genesisId.toString(),
                revision.hash,
                alreadyHashed = true,
            )
        }
    },
    object : ConversationCapability {
        override val descriptor = descriptor(
            CAPABILITY_ADMIT_PROJECT_GENESIS,
            "Admit one completed project genesis revision without altering its content.",
            mutation = true,
            payloadSchema = "{\"projectId\": positive integer}",
            states = executableStates,
            owningService = "WorkspaceStore",
            resultType = "PROJECT_GENESIS_REVISION",
            idempotencyStrategy = "COMMAND_REFERENCE",
        )

        override suspend fun reconcile(
            command: ConversationCommandProposal,
            objective: ConversationObjectiveRevision?,
        ): ConversationCapabilityResult? = workspace.snapshot(MESSAGE_READY).projectGenesis
            .mapNotNull { it.revision }
            .singleOrNull { it.phase == GENESIS_READY && it.conversationCommand == commandReference(command) }
            ?.let { success("Recovered admitted project genesis revision ${it.revision}.", "PROJECT_GENESIS_REVISION", it.genesisId.toString(), it.hash, alreadyHashed = true) }

        override suspend fun execute(payloadJson: String, objective: ConversationObjectiveRevision?, command: ConversationCommandProposal): ConversationCapabilityResult {
            val payload = json.decodeFromString<ProjectCapabilityPayload>(payloadJson)
            require(payload.projectId > 0 && (objective?.projectId == null || objective.projectId == payload.projectId)) {
                "Genesis admission crossed the selected objective's project authority."
            }
            val result = workspace.admitProjectGenesis(payload.projectId, commandReference(command))
            if (result.status != ProjectGenesisStatus.ADMITTED) {
                return failure("Project genesis was not admitted: ${result.status}.", "PROJECT_GENESIS", payload.projectId.toString())
            }
            val revision = result.snapshot.projectGenesis.single { it.projectId == payload.projectId }.revision
                ?: return failure("The admitted genesis revision could not be resolved.", "PROJECT_GENESIS", payload.projectId.toString())
            return success(
                "Project ${payload.projectId} genesis is admitted at revision ${revision.revision}.",
                "PROJECT_GENESIS_REVISION",
                revision.genesisId.toString(),
                revision.hash,
                alreadyHashed = true,
            )
        }
    },
    definitionIntelligence?.let { service ->
        object : ConversationCapability {
            override val descriptor = descriptor(
                CAPABILITY_GENERATE_WORK_DEFINITION,
                "Generate a governed work-definition proposal for one task or bug.",
                mutation = true,
                payloadSchema = "{\"workItemId\": positive integer}",
                states = executableStates,
                owningService = "DefinitionIntelligenceService",
                resultType = "WORK_DEFINITION_PROPOSAL",
                idempotencyStrategy = "COMMAND_REFERENCE",
            )

            override suspend fun reconcile(
                command: ConversationCommandProposal,
                objective: ConversationObjectiveRevision?,
            ): ConversationCapabilityResult? = workspace.snapshot(MESSAGE_READY).definitionProposals
                .map { it.proposal }
                .singleOrNull { it.conversationCommand == commandReference(command) }
                ?.let { success("Recovered work-definition proposal ${it.proposalId}.", "WORK_DEFINITION_PROPOSAL", it.proposalId.toString(), it.hash, alreadyHashed = true) }

            override suspend fun execute(payloadJson: String, objective: ConversationObjectiveRevision?, command: ConversationCommandProposal): ConversationCapabilityResult {
                val payload = json.decodeFromString<WorkItemCapabilityPayload>(payloadJson)
                require(payload.workItemId > 0)
                val result = service.propose(payload.workItemId, commandReference(command))
                if (result.status != ProposalGenerationStatus.CREATED) {
                    return failure("Work-definition proposal was not created: ${result.status}.", "WORK_ITEM", payload.workItemId.toString())
                }
                val proposal = result.snapshot.definitionProposals
                    .filter { it.proposal.workItemId == payload.workItemId }
                    .maxByOrNull { it.proposal.revision }
                    ?: return failure("The created work-definition proposal could not be resolved.", "WORK_ITEM", payload.workItemId.toString())
                return success(
                    "Work-definition proposal ${proposal.proposal.proposalId} was created and remains subject to explicit acceptance.",
                    "WORK_DEFINITION_PROPOSAL",
                    proposal.proposal.proposalId.toString(),
                    proposal.proposal.hash,
                    alreadyHashed = true,
                )
            }
        }
    },
    object : ConversationCapability {
        override val descriptor = descriptor(
            CAPABILITY_ACCEPT_WORK_DEFINITION,
            "Accept one exact existing work-definition proposal unchanged.",
            mutation = true,
            payloadSchema = "{\"proposalId\": positive integer}",
            states = executableStates,
            owningService = "WorkspaceStore",
            resultType = "WORK_DEFINITION",
            idempotencyStrategy = "SOURCE_PROPOSAL",
        )

        override suspend fun reconcile(
            command: ConversationCommandProposal,
            objective: ConversationObjectiveRevision?,
        ): ConversationCapabilityResult? {
            val payload = json.decodeFromString<ProposalCapabilityPayload>(command.payloadJson)
            val proposal = workspace.snapshot(MESSAGE_READY).definitionProposals
                .singleOrNull { it.proposal.proposalId == payload.proposalId }?.proposal ?: return null
            return workspace.snapshot(MESSAGE_READY).workDefinitions
                .singleOrNull { it.sourceProposal?.proposalId == proposal.proposalId && it.sourceProposal.proposalHash == proposal.hash }
                ?.let { success("Recovered accepted work definition ${it.definitionId}.", "WORK_DEFINITION", it.definitionId.toString(), it.hash, alreadyHashed = true) }
        }

        override suspend fun execute(payloadJson: String, objective: ConversationObjectiveRevision?, command: ConversationCommandProposal): ConversationCapabilityResult {
            val payload = json.decodeFromString<ProposalCapabilityPayload>(payloadJson)
            require(payload.proposalId > 0)
            val before = workspace.snapshot(MESSAGE_READY).definitionProposals
                .singleOrNull { it.proposal.proposalId == payload.proposalId }
                ?: return failure("Work-definition proposal ${payload.proposalId} does not exist.", "WORK_DEFINITION_PROPOSAL", payload.proposalId.toString())
            val result = workspace.acceptDefinitionProposal(payload.proposalId)
            if (result.status != WorkDefinitionStatus.RECORDED) {
                return failure("Work-definition proposal was not accepted: ${result.status}.", "WORK_DEFINITION_PROPOSAL", payload.proposalId.toString(), before.proposal.hash)
            }
            val definition = result.snapshot.workDefinitions
                .filter { it.workItemId == before.proposal.workItemId }
                .maxByOrNull { it.revision }
                ?: return failure("The accepted work definition could not be resolved.", "WORK_DEFINITION_PROPOSAL", payload.proposalId.toString(), before.proposal.hash)
            return success(
                "Work definition ${definition.definitionId} was accepted from proposal ${payload.proposalId}.",
                "WORK_DEFINITION",
                definition.definitionId.toString(),
                definition.hash,
                alreadyHashed = true,
            )
        }
    },
    circuitIntelligence?.let { service ->
        object : ConversationCapability {
            override val descriptor = descriptor(
                CAPABILITY_GENERATE_STAGED_PLAN,
                "Generate a governed staged delivery plan proposal for one story or epic.",
                mutation = true,
                payloadSchema = "{\"scopeId\": positive integer}",
                states = executableStates,
                owningService = "CircuitIntelligenceService",
                resultType = "CIRCUIT_PROPOSAL",
                idempotencyStrategy = "COMMAND_REFERENCE",
            )

            override suspend fun reconcile(
                command: ConversationCommandProposal,
                objective: ConversationObjectiveRevision?,
            ): ConversationCapabilityResult? = workspace.snapshot(MESSAGE_READY).circuitProposals
                .map { it.proposal }
                .singleOrNull { it.conversationCommand == commandReference(command) }
                ?.let { success("Recovered staged-plan proposal ${it.proposalId}.", "CIRCUIT_PROPOSAL", it.proposalId.toString(), it.hash, alreadyHashed = true) }

            override suspend fun execute(payloadJson: String, objective: ConversationObjectiveRevision?, command: ConversationCommandProposal): ConversationCapabilityResult {
                val payload = json.decodeFromString<ScopeCapabilityPayload>(payloadJson)
                require(payload.scopeId > 0)
                val result = service.propose(payload.scopeId, commandReference(command))
                if (result.status != CircuitGenerationStatus.CREATED) {
                    return failure("Staged-plan proposal was not created: ${result.status}.", "WORKSPACE_SCOPE", payload.scopeId.toString())
                }
                val proposal = result.snapshot.circuitProposals
                    .filter { it.proposal.scopeId == payload.scopeId }
                    .maxByOrNull { it.proposal.revision }
                    ?: return failure("The created staged-plan proposal could not be resolved.", "WORKSPACE_SCOPE", payload.scopeId.toString())
                return success(
                    "Staged-plan proposal ${proposal.proposal.proposalId} was created and remains subject to explicit acceptance.",
                    "CIRCUIT_PROPOSAL",
                    proposal.proposal.proposalId.toString(),
                    proposal.proposal.hash,
                    alreadyHashed = true,
                )
            }
        }
    },
    object : ConversationCapability {
        override val descriptor = descriptor(
            CAPABILITY_ACCEPT_STAGED_PLAN,
            "Accept one exact existing staged delivery plan proposal.",
            mutation = true,
            payloadSchema = "{\"proposalId\": positive integer}",
            states = executableStates,
            owningService = "WorkspaceStore",
            resultType = "STAGED_DELIVERY_PLAN",
            idempotencyStrategy = "SOURCE_PROPOSAL",
        )

        override suspend fun reconcile(
            command: ConversationCommandProposal,
            objective: ConversationObjectiveRevision?,
        ): ConversationCapabilityResult? {
            val payload = json.decodeFromString<ProposalCapabilityPayload>(command.payloadJson)
            val proposal = workspace.snapshot(MESSAGE_READY).circuitProposals
                .singleOrNull { it.proposal.proposalId == payload.proposalId }?.proposal ?: return null
            return workspace.snapshot(MESSAGE_READY).stagedPlans.map { it.plan }
                .singleOrNull { it.sourceProposal?.proposalId == proposal.proposalId && it.sourceProposal.proposalHash == proposal.hash }
                ?.let { success("Recovered accepted staged plan ${it.planId}.", "STAGED_DELIVERY_PLAN", it.planId.toString(), it.hash, alreadyHashed = true) }
        }

        override suspend fun execute(payloadJson: String, objective: ConversationObjectiveRevision?, command: ConversationCommandProposal): ConversationCapabilityResult {
            val payload = json.decodeFromString<ProposalCapabilityPayload>(payloadJson)
            require(payload.proposalId > 0)
            val proposal = workspace.snapshot(MESSAGE_READY).circuitProposals
                .singleOrNull { it.proposal.proposalId == payload.proposalId }
                ?: return failure("Staged-plan proposal ${payload.proposalId} does not exist.", "CIRCUIT_PROPOSAL", payload.proposalId.toString())
            val result = workspace.acceptCircuitProposal(payload.proposalId)
            if (result.status != StagedPlanStatus.ACCEPTED) {
                return failure("Staged-plan proposal was not accepted: ${result.status}.", "CIRCUIT_PROPOSAL", payload.proposalId.toString(), proposal.proposal.hash)
            }
            val plan = result.snapshot.stagedPlans.maxByOrNull { it.plan.planId }
                ?: return failure("The accepted staged plan could not be resolved.", "CIRCUIT_PROPOSAL", payload.proposalId.toString(), proposal.proposal.hash)
            return success(
                "Staged delivery plan ${plan.plan.planId} was accepted from proposal ${payload.proposalId}.",
                "STAGED_DELIVERY_PLAN",
                plan.plan.planId.toString(),
                plan.plan.hash,
                alreadyHashed = true,
            )
        }
    },
    object : ConversationCapability {
        override val descriptor = descriptor(
            CAPABILITY_START_WORKFLOW,
            "Start the admitted execution workflow for one task or bug.",
            mutation = true,
            payloadSchema = "{\"workItemId\": positive integer}",
            states = setOf(OBJECTIVE_READY, OBJECTIVE_ACTIVE),
            owningService = "WorkspaceStore",
            resultType = "WORKFLOW_RUN",
            idempotencyStrategy = "WORK_ITEM_SINGLETON",
        )

        override suspend fun reconcile(
            command: ConversationCommandProposal,
            objective: ConversationObjectiveRevision?,
        ): ConversationCapabilityResult? {
            val payload = json.decodeFromString<WorkItemCapabilityPayload>(command.payloadJson)
            return workspace.snapshot(MESSAGE_READY).workflowRuns.singleOrNull {
                it.context.workItemId == payload.workItemId && it.conversationCommand == commandReference(command)
            }
                ?.let { success("Recovered workflow run ${it.runId}.", "WORKFLOW_RUN", it.runId.toString(), json.encodeToString(it), it.context.repository.commitHash) }
        }

        override suspend fun execute(payloadJson: String, objective: ConversationObjectiveRevision?, command: ConversationCommandProposal): ConversationCapabilityResult {
            val payload = json.decodeFromString<WorkItemCapabilityPayload>(payloadJson)
            require(payload.workItemId > 0)
            val result = workspace.startWorkflow(payload.workItemId, commandReference(command))
            if (result.status !in setOf(WorkflowStartStatus.CREATED, WorkflowStartStatus.ALREADY_STARTED)) {
                return failure("Workflow did not start: ${result.status}.", "WORK_ITEM", payload.workItemId.toString())
            }
            val run = result.snapshot.workflowRuns.lastOrNull { it.context.workItemId == payload.workItemId }
                ?: return failure("The workflow run could not be resolved.", "WORK_ITEM", payload.workItemId.toString())
            val encoded = json.encodeToString(run)
            return success(
                "Workflow run ${run.runId} is ${run.state} for work item ${payload.workItemId}.",
                "WORKFLOW_RUN",
                run.runId.toString(),
                encoded,
                repositoryRevision = run.context.repository.commitHash,
            )
        }
    },
    object : ConversationCapability {
        override val descriptor = descriptor(
            CAPABILITY_CANCEL_WORKFLOW,
            "Cancel one exact active workflow run.",
            mutation = true,
            payloadSchema = "{\"runId\": positive integer}",
            states = setOf(OBJECTIVE_READY, OBJECTIVE_ACTIVE, OBJECTIVE_BLOCKED, OBJECTIVE_PAUSED),
            owningService = "WorkspaceStore",
            resultType = "WORKFLOW_RUN",
            idempotencyStrategy = "RUN_STATE",
        )

        override suspend fun reconcile(
            command: ConversationCommandProposal,
            objective: ConversationObjectiveRevision?,
        ): ConversationCapabilityResult? {
            val payload = json.decodeFromString<RunCapabilityPayload>(command.payloadJson)
            return workspace.snapshot(MESSAGE_READY).workflowRuns.singleOrNull { it.runId == payload.runId && it.state == "CANCELLED" }
                ?.let { success("Recovered cancelled workflow run ${it.runId}.", "WORKFLOW_RUN", it.runId.toString(), json.encodeToString(it), it.context.repository.commitHash) }
        }

        override suspend fun execute(payloadJson: String, objective: ConversationObjectiveRevision?, command: ConversationCommandProposal): ConversationCapabilityResult {
            val payload = json.decodeFromString<RunCapabilityPayload>(payloadJson)
            require(payload.runId > 0)
            val result = workspace.cancelWorkflow(payload.runId)
            if (result.status != WorkflowMutationStatus.RECORDED) {
                return failure("Workflow was not cancelled: ${result.status}.", "WORKFLOW_RUN", payload.runId.toString())
            }
            val run = result.snapshot.workflowRuns.singleOrNull { it.runId == payload.runId }
                ?: return failure("The cancelled workflow run could not be resolved.", "WORKFLOW_RUN", payload.runId.toString())
            return success(
                "Workflow run ${payload.runId} is ${run.state}.",
                "WORKFLOW_RUN",
                payload.runId.toString(),
                json.encodeToString(run),
                repositoryRevision = run.context.repository.commitHash,
            )
        }
    },
    companyCircuit?.let { service ->
        object : ConversationCapability {
            override val descriptor = descriptor(
                CAPABILITY_START_COMPANY,
                "Start the governed company circuit for one admitted project genesis.",
                mutation = true,
                payloadSchema = "{\"projectId\": positive integer}",
                states = executableStates,
                owningService = "CompanyCircuitService",
                resultType = "COMPANY_PROJECT",
                idempotencyStrategy = "PROJECT_SINGLETON",
            )

            override suspend fun reconcile(
                command: ConversationCommandProposal,
                objective: ConversationObjectiveRevision?,
            ): ConversationCapabilityResult? {
                val payload = json.decodeFromString<ProjectCapabilityPayload>(command.payloadJson)
                return companyControl?.projectViews()?.singleOrNull { it.projectId == payload.projectId }
                    ?.let { success("Recovered company circuit for project ${it.projectId}.", "COMPANY_PROJECT", it.projectId.toString(), json.encodeToString(it)) }
            }

            override suspend fun execute(payloadJson: String, objective: ConversationObjectiveRevision?, command: ConversationCommandProposal): ConversationCapabilityResult {
                val payload = json.decodeFromString<ProjectCapabilityPayload>(payloadJson)
                require(payload.projectId > 0 && (objective?.projectId == null || objective.projectId == payload.projectId))
                val result = service.start(payload.projectId)
                if (result.status !in setOf(CompanyCircuitStatus.STARTED, CompanyCircuitStatus.ALREADY_STARTED) || result.project == null) {
                    return failure("Company circuit did not start: ${result.status}. ${result.diagnostic}", "PROJECT", payload.projectId.toString())
                }
                return success(
                    "Company circuit for project ${payload.projectId} is in ${result.project.phase}.",
                    "COMPANY_PROJECT",
                    payload.projectId.toString(),
                    json.encodeToString(result.project),
                )
            }
        }
    },
    companyControl?.let { service ->
        object : ConversationCapability {
            override val descriptor = descriptor(
                CAPABILITY_PROMOTE_RUN,
                "Promote one independently accepted run into its local destination repository.",
                mutation = true,
                payloadSchema = "{\"runId\": positive integer}",
                states = setOf(OBJECTIVE_ACTIVE, OBJECTIVE_BLOCKED),
                owningService = "CompanyControlService",
                resultType = "LOCAL_PROMOTION",
                idempotencyStrategy = "RUN_ACCEPTANCE_SINGLETON",
            )

            override suspend fun reconcile(
                command: ConversationCommandProposal,
                objective: ConversationObjectiveRevision?,
            ): ConversationCapabilityResult? {
                val payload = json.decodeFromString<RunCapabilityPayload>(command.payloadJson)
                return service.projectViews().flatMap { it.promotions }.singleOrNull { it.runId == payload.runId }
                    ?.let { success("Recovered promotion ${it.promotionId} for run ${it.runId}.", "LOCAL_PROMOTION", it.promotionId.toString(), it.hash, it.destinationRevision, true) }
            }

            override suspend fun execute(payloadJson: String, objective: ConversationObjectiveRevision?, command: ConversationCommandProposal): ConversationCapabilityResult {
                val payload = json.decodeFromString<RunCapabilityPayload>(payloadJson)
                require(payload.runId > 0)
                val result = service.promote(payload.runId)
                if (result.status != CompanyMutationStatus.RECORDED || result.project == null) {
                    return failure("Run was not promoted: ${result.status}.", "WORKFLOW_RUN", payload.runId.toString())
                }
                val promotion = result.project.promotions.lastOrNull { it.runId == payload.runId }
                    ?: return failure("The promotion record could not be resolved.", "WORKFLOW_RUN", payload.runId.toString())
                return success(
                    "Run ${payload.runId} was promoted to ${promotion.destinationRevision}.",
                    "LOCAL_PROMOTION",
                    promotion.promotionId.toString(),
                    promotion.hash,
                    repositoryRevision = promotion.destinationRevision,
                    alreadyHashed = true,
                )
            }
        }
    },
))

private val executableStates = setOf(OBJECTIVE_READY, OBJECTIVE_ACTIVE, OBJECTIVE_BLOCKED)

private fun commandReference(command: ConversationCommandProposal) = ConversationCommandReference(
    command.commandId,
    command.hash,
    command.capabilityId,
)

private fun descriptor(
    id: String,
    description: String,
    mutation: Boolean,
    payloadSchema: String,
    states: Set<String> = emptySet(),
    owningService: String,
    resultType: String,
    idempotencyStrategy: String,
) = ConversationCapabilityDescriptor(
    id,
    description,
    mutation,
    payloadSchema,
    states,
    owningService,
    if (mutation) "EXACT_COMMAND_HASH" else "NONE",
    resultType,
    idempotencyStrategy,
)

private fun success(
    summary: String,
    type: String,
    id: String,
    contentOrHash: String,
    repositoryRevision: String? = null,
    alreadyHashed: Boolean = false,
) = ConversationCapabilityResult(
    true,
    summary,
    type,
    id,
    if (alreadyHashed) contentOrHash else conversationAuthorityHash(contentOrHash),
    repositoryRevision,
)

private fun failure(
    summary: String,
    type: String,
    id: String,
    hash: String = conversationAuthorityHash("$type:$id:$summary"),
) = ConversationCapabilityResult(false, summary, type, id, hash)

private fun requireEmptyPayload(payloadJson: String, json: Json) {
    require(json.parseToJsonElement(payloadJson).toString() == "{}") { "This capability accepts an empty JSON object." }
}