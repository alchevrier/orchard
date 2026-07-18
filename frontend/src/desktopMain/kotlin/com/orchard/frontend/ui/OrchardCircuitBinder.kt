package com.orchard.frontend.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.AlertDialog
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.RadioButton
import androidx.compose.material.Slider
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orchard.frontend.network.DesktopNetworkClient
import com.orchard.frontend.network.RepositoryResponse
import com.orchard.frontend.network.WorkspaceSnapshotResponse
import com.orchard.frontend.network.WorkflowRunResponse
import com.orchard.frontend.network.WorkDefinitionResponse
import com.orchard.frontend.network.WorkDefinitionSubmissionRequest
import com.orchard.frontend.network.AcceptanceCriterionRequest
import com.orchard.frontend.network.DefinitionProposalViewResponse
import com.orchard.frontend.network.ModelCapabilityProfileResponse
import com.orchard.frontend.network.ModelProfileConfigurationResponse
import com.orchard.frontend.network.ModelProfileOverrideRequest
import com.orchard.frontend.network.ModelProviderCatalogResponse
import com.orchard.frontend.network.ModelEndpointInspectionResponse
import com.orchard.frontend.network.MachineResourceConfigurationResponse
import com.orchard.frontend.network.MachineUsagePolicyRequest
import com.orchard.frontend.network.StagedDeliveryPlanSubmissionRequest
import com.orchard.frontend.network.StagedDeliveryPlanViewResponse
import com.orchard.frontend.network.StagedPlanArtifactRequest
import com.orchard.frontend.network.StagedPlanArtifactRequirementRequest
import com.orchard.frontend.network.StagedPlanNodeSubmissionRequest
import com.orchard.frontend.network.StagedPlanStageSubmissionRequest
import com.orchard.frontend.network.StageExecutionWorkflowDefinitionResponse
import com.orchard.frontend.network.CircuitProposalViewResponse
import com.orchard.frontend.network.CircuitProposalReferenceRequest
import com.orchard.frontend.network.CircuitDispatchViewResponse
import com.orchard.frontend.network.ProjectGenesisSubmissionRequest
import com.orchard.frontend.network.GenesisProposalResponse
import com.orchard.frontend.network.CompanyProjectResponse
import com.orchard.frontend.network.EngineeringStandardSubmissionRequest
import com.orchard.frontend.network.EngineeringStandardsViewResponse
import com.orchard.frontend.network.RemediationCampaignViewResponse
import com.orchard.frontend.network.CampaignResolutionViewResponse
import com.orchard.frontend.network.RepositoryExecutionPlanResponse
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import javax.swing.JFileChooser
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.roundToInt

private const val PROJECT = "PROJECT"
private const val EPIC = "EPIC"
private const val STORY = "STORY"
private const val TASK = "TASK"
private const val BUG = "BUG"

@Composable
fun App() {
    val networkClient = remember { DesktopNetworkClient() }
    val binder = remember { OrchardCircuitBinder(networkClient) }
    DisposableEffect(networkClient) {
        onDispose { networkClient.close() }
    }
    binder.render()
}

private data class WorkspaceEntity(
    val id: Int,
    val type: String,
    val title: String,
    val parentId: Int,
    val status: Int,
)

private data class WorkspaceSnapshot(
    val entities: List<WorkspaceEntity>,
    val focusId: Int,
    val message: String,
    val repositories: Map<Int, RepositoryResponse>,
    val workflowRuns: List<WorkflowRunResponse>,
    val workDefinitions: List<WorkDefinitionResponse>,
    val definitionProposals: List<DefinitionProposalViewResponse>,
    val stagedPlans: List<StagedDeliveryPlanViewResponse>,
    val circuitProposals: List<CircuitProposalViewResponse>,
    val stageWorkflows: List<StageExecutionWorkflowDefinitionResponse>,
    val circuitDispatches: List<CircuitDispatchViewResponse>,
    val modelProfiles: List<ModelCapabilityProfileResponse>,
)

class OrchardCircuitBinder(private val networkClient: DesktopNetworkClient) {
    private val responseMutex = Mutex()
    private val requestSequence = AtomicLong()
    private var appliedResponseSequence = 0L
    private var response by mutableStateOf(WorkspaceSnapshotResponse())
    private var companyProjects by mutableStateOf<List<CompanyProjectResponse>>(emptyList())
    private var executionPlans by mutableStateOf<List<RepositoryExecutionPlanResponse>>(emptyList())

    @Composable
    fun render() {
        var isSubmitting by remember { mutableStateOf(false) }
        var isBindingRepository by remember { mutableStateOf(false) }
        var isGeneratingProposal by remember { mutableStateOf(false) }
        var engineeringStandards by remember { mutableStateOf<EngineeringStandardsViewResponse?>(null) }
        var remediationCampaigns by remember { mutableStateOf(emptyList<RemediationCampaignViewResponse>()) }
        var campaignResolutions by remember { mutableStateOf(emptyList<CampaignResolutionViewResponse>()) }
        var isSavingStandard by remember { mutableStateOf(false) }
        var isScanningConformance by remember { mutableStateOf(false) }
        var isAdmittingConformance by remember { mutableStateOf(false) }
        var isResolvingCampaign by remember { mutableStateOf(false) }
        var genesisProposal by remember { mutableStateOf<GenesisProposalResponse?>(null) }
        var requestError by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()

        LaunchedEffect(Unit) {
            refreshWorkspace().onFailure { requestError = it.message ?: "Unable to reach Orchard." }
        }

        val snapshot = remember(response) { readWorkspaceSnapshot(response) }
        val projects = snapshot.entities.filter { it.type == PROJECT }
        val project = projects.firstOrNull { it.id == projectForFocus(snapshot) } ?: projects.firstOrNull()
        val projectId = project?.id ?: 0
        val epics = snapshot.entities
            .filter { it.type == EPIC && it.parentId == projectId }
            .map { it.id to it.title }
        val genesis = response.projectGenesis.singleOrNull { it.projectId == projectId }
        val repository = snapshot.repositories[projectId]
        val company = companyProjects.singleOrNull { it.projectId == projectId }
        val executionPlan = executionPlans.filter { it.projectId == projectId }.maxByOrNull { it.planId }

        LaunchedEffect(projectId, repository?.available) {
            if (projectId > 0 && repository?.available == true) {
                runCatching {
                    engineeringStandards = networkClient.getEngineeringStandards(projectId)
                    remediationCampaigns = networkClient.getRemediationCampaigns(projectId)
                    campaignResolutions = networkClient.getCampaignResolutions(projectId)
                }.onFailure { requestError = it.message ?: "Unable to load engineering standards." }
            } else {
                engineeringStandards = null
                remediationCampaigns = emptyList()
                campaignResolutions = emptyList()
            }
        }

        LaunchedEffect(genesis?.phase, genesis?.revision?.hash) {
            genesisProposal = null
        }

        fun runSubmission(operation: suspend () -> Result<Unit>) {
            if (isSubmitting) return
            isSubmitting = true
            requestError = null
            scope.launch {
                operation().onFailure { requestError = it.message ?: "Orchard could not advance this decision." }
                isSubmitting = false
            }
        }

        OrchardTheme {
            GuidedGenesisWorkspace(
                projectId = projectId,
                projectTitle = project?.title.orEmpty(),
                epics = epics,
                genesis = genesis,
                repositoryPath = repository?.path,
                repositoryAvailable = repository?.available == true,
                message = requestError ?: snapshot.message,
                isSubmitting = isSubmitting,
                isBindingRepository = isBindingRepository,
                isGeneratingProposal = isGeneratingProposal,
                proposal = genesisProposal,
                company = company,
                executionPlan = executionPlan,
                engineeringStandards = engineeringStandards,
                remediationCampaigns = remediationCampaigns,
                campaignResolutions = campaignResolutions,
                isSavingStandard = isSavingStandard,
                isScanningConformance = isScanningConformance,
                isAdmittingConformance = isAdmittingConformance,
                isResolvingCampaign = isResolvingCampaign,
                onCreateProject = { name ->
                    runSubmission { sendArchitectPrompt("Create a project named \"$name\".") }
                },
                onCreateEpic = { title ->
                    runSubmission {
                        sendArchitectPrompt("Create an epic named \"$title\" in project ID $projectId.")
                    }
                },
                onBindRepository = {
                    val selectedPath = chooseRepositoryDirectory(repository?.path)
                    if (projectId != 0 && selectedPath != null && !isBindingRepository) {
                        isBindingRepository = true
                        requestError = null
                        scope.launch {
                            bindRepository(projectId, selectedPath)
                                .onFailure { requestError = it.message ?: "Unable to bind the repository." }
                            isBindingRepository = false
                        }
                    }
                },
                onGenerateProposal = { prompt ->
                    if (!isGeneratingProposal && prompt.isNotBlank()) {
                        isGeneratingProposal = true
                        requestError = null
                        scope.launch {
                            runCatching { networkClient.proposeProjectGenesis(projectId, prompt) }
                                .onSuccess { genesisProposal = it }
                                .onFailure { requestError = it.message ?: "The Genesis Architect could not form a proposal." }
                            isGeneratingProposal = false
                        }
                    }
                },
                onApplyProposal = {
                    genesisProposal?.let { candidate ->
                        runSubmission { advanceProjectGenesis(projectId, candidate.submission) }
                    }
                },
                onAdvance = { submission -> runSubmission { advanceProjectGenesis(projectId, submission) } },
                onAdmit = { runSubmission { admitProjectGenesis(projectId) } },
                onStartCompany = { runSubmission { startCompany(projectId) } },
                onPromote = { runId -> runSubmission { promoteCompanyRun(runId) } },
                onSaveStandard = { submission ->
                    if (!isSavingStandard) {
                        isSavingStandard = true
                        requestError = null
                        scope.launch {
                            runCatching { networkClient.updateEngineeringStandard(projectId, submission) }
                                .onSuccess { engineeringStandards = networkClient.getEngineeringStandards(projectId) }
                                .onFailure { requestError = it.message ?: "Unable to save engineering standard." }
                            isSavingStandard = false
                        }
                    }
                },
                onScanConformance = {
                    if (!isScanningConformance) {
                        isScanningConformance = true
                        requestError = null
                        scope.launch {
                            runCatching { networkClient.runConformanceScan(projectId) }
                                .onSuccess { engineeringStandards = networkClient.getEngineeringStandards(projectId) }
                                .onFailure { requestError = it.message ?: "Unable to scan repository conformance." }
                            isScanningConformance = false
                        }
                    }
                },
                onAdmitConformance = { scanId ->
                    if (!isAdmittingConformance) {
                        isAdmittingConformance = true
                        requestError = null
                        scope.launch {
                            runCatching { networkClient.admitConformanceBacklog(scanId) }
                                .onSuccess {
                                    engineeringStandards = networkClient.getEngineeringStandards(projectId)
                                    remediationCampaigns = networkClient.getRemediationCampaigns(projectId)
                                    refreshWorkspace().getOrThrow()
                                }
                                .onFailure { requestError = it.message ?: "Unable to admit the conformance backlog." }
                            isAdmittingConformance = false
                        }
                    }
                },
                onProposeCampaignResolution = { caseId ->
                    if (!isResolvingCampaign) {
                        isResolvingCampaign = true
                        requestError = null
                        scope.launch {
                            runCatching { networkClient.proposeCampaignResolution(caseId) }
                                .onSuccess { campaignResolutions = networkClient.getCampaignResolutions(projectId) }
                                .onFailure { requestError = it.message ?: "The Architect could not propose campaign recovery." }
                            isResolvingCampaign = false
                        }
                    }
                },
                onAdmitCampaignResolution = { proposalId ->
                    if (!isResolvingCampaign) {
                        isResolvingCampaign = true
                        requestError = null
                        scope.launch {
                            runCatching { networkClient.admitCampaignResolution(proposalId) }
                                .onSuccess {
                                    campaignResolutions = networkClient.getCampaignResolutions(projectId)
                                    remediationCampaigns = networkClient.getRemediationCampaigns(projectId)
                                    refreshWorkspace().getOrThrow()
                                }
                                .onFailure { requestError = it.message ?: "Unable to admit the campaign resolution." }
                            isResolvingCampaign = false
                        }
                    }
                },
                onRefresh = {
                    if (!isSubmitting) {
                        scope.launch {
                            requestError = null
                            runCatching {
                                refreshWorkspace().getOrThrow()
                                if (projectId > 0 && repository?.available == true) {
                                    engineeringStandards = networkClient.getEngineeringStandards(projectId)
                                    remediationCampaigns = networkClient.getRemediationCampaigns(projectId)
                                    campaignResolutions = networkClient.getCampaignResolutions(projectId)
                                }
                            }.onFailure { requestError = it.message ?: "Unable to refresh Orchard." }
                        }
                    }
                },
            )
        }
    }

    @Composable
    private fun renderLegacyWorkspace() {
        var prompt by remember { mutableStateOf("") }
        var isSubmitting by remember { mutableStateOf(false) }
        var isBindingRepository by remember { mutableStateOf(false) }
        var startingWorkItemId by remember { mutableStateOf(0) }
        var cancellingRunId by remember { mutableStateOf(0L) }
        var definingWorkItemId by remember { mutableStateOf(0) }
        var isSubmittingDefinition by remember { mutableStateOf(false) }
        var analyzingWorkItemIds by remember { mutableStateOf(emptySet<Int>()) }
        var requestError by remember { mutableStateOf<String?>(null) }
        var showModelSettings by remember { mutableStateOf(false) }
        var modelProfileConfigurations by remember { mutableStateOf(emptyList<ModelProfileConfigurationResponse>()) }
        var modelProviderCatalog by remember { mutableStateOf<ModelProviderCatalogResponse?>(null) }
        var modelProviderInspections by remember { mutableStateOf(emptyList<ModelEndpointInspectionResponse>()) }
        var machineResourceConfiguration by remember { mutableStateOf<MachineResourceConfigurationResponse?>(null) }
        var isLoadingModelSettings by remember { mutableStateOf(false) }
        var isSavingModelSettings by remember { mutableStateOf(false) }
        var isSavingMachinePolicy by remember { mutableStateOf(false) }
        var isSavingModelProvider by remember { mutableStateOf(false) }
        var isInspectingModelProvider by remember { mutableStateOf(false) }
        var selectedProjectId by remember { mutableStateOf(0) }
        var selectedEpicId by remember { mutableStateOf(0) }
        var planningScopeId by remember { mutableStateOf(0) }
        var isSavingStagedPlan by remember { mutableStateOf(false) }
        var isGeneratingCircuit by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        LaunchedEffect(Unit) {
            refreshWorkspace().onFailure { requestError = it.message ?: "Unable to reach Orchard." }
        }

        val snapshot = remember(response) { readWorkspaceSnapshot(response) }
        val projects = snapshot.entities.filter { it.type == PROJECT }
        val activeProjectId = selectedProjectId.takeIf { id -> projects.any { it.id == id } }
            ?: projectForFocus(snapshot)
            ?: projects.firstOrNull()?.id
            ?: 0
        val epics = snapshot.entities.filter { it.type == EPIC && it.parentId == activeProjectId }
        val activeEpicId = selectedEpicId.takeIf { id -> epics.any { it.id == id } }
            ?: epicForFocus(snapshot)
            ?: epics.firstOrNull()?.id
            ?: 0
        val activeRepository = snapshot.repositories[activeProjectId]

        fun submitPrompt() {
            val message = prompt.trim()
            if (message.isEmpty() || isSubmitting) return
            prompt = ""
            isSubmitting = true
            requestError = null
            scope.launch {
                sendArchitectPrompt(message)
                    .onFailure { requestError = it.message ?: "The Architect request failed." }
                isSubmitting = false
            }
        }

        OrchardTheme {
            Row(modifier = Modifier.fillMaxSize().background(OrchardColors.canvas)) {
                ProjectSidebar(
                    projects = projects,
                    selectedProjectId = activeProjectId,
                    onSelect = {
                        selectedProjectId = it
                        selectedEpicId = 0
                    },
                )
                Divider(modifier = Modifier.fillMaxHeight().width(1.dp), color = OrchardColors.divider)
                WorkspaceBoard(
                    modifier = Modifier.weight(1f),
                    snapshot = snapshot,
                    projectId = activeProjectId,
                    epics = epics,
                    epicId = activeEpicId,
                    repository = activeRepository,
                    isBindingRepository = isBindingRepository,
                    startingWorkItemId = startingWorkItemId,
                    cancellingRunId = cancellingRunId,
                    analyzingWorkItemIds = analyzingWorkItemIds,
                    onSelectEpic = { selectedEpicId = it },
                    onBindRepository = {
                        val selectedPath = chooseRepositoryDirectory(activeRepository?.path)
                        if (activeProjectId != 0 && selectedPath != null) {
                            isBindingRepository = true
                            requestError = null
                            scope.launch {
                                bindRepository(activeProjectId, selectedPath)
                                    .onFailure { requestError = it.message ?: "Unable to bind the repository." }
                                isBindingRepository = false
                            }
                        }
                    },
                    onStartWorkflow = { workItemId ->
                        if (startingWorkItemId == 0) {
                            startingWorkItemId = workItemId
                            requestError = null
                            scope.launch {
                                startWorkflow(workItemId)
                                    .onFailure { requestError = it.message ?: "Unable to start the workflow." }
                                startingWorkItemId = 0
                            }
                        }
                    },
                    onCancelWorkflow = { runId ->
                        if (cancellingRunId == 0L) {
                            cancellingRunId = runId
                            requestError = null
                            scope.launch {
                                cancelWorkflow(runId)
                                    .onFailure { requestError = it.message ?: "Unable to cancel the workflow." }
                                cancellingRunId = 0L
                            }
                        }
                    },
                    onDefineWork = { workItemId -> definingWorkItemId = workItemId },
                    onAnalyzeWork = { workItemId ->
                        if (workItemId !in analyzingWorkItemIds) {
                            analyzingWorkItemIds = analyzingWorkItemIds + workItemId
                            requestError = null
                            scope.launch {
                                generateDefinitionProposal(workItemId)
                                    .onSuccess { definingWorkItemId = workItemId }
                                    .onFailure { requestError = it.message ?: "The local model could not propose a definition." }
                                analyzingWorkItemIds = analyzingWorkItemIds - workItemId
                            }
                        }
                    },
                    onRefresh = {
                        scope.launch {
                            requestError = null
                            refreshWorkspace().onFailure {
                                requestError = it.message ?: "Unable to refresh the workspace."
                            }
                        }
                    },
                    onOpenSettings = {
                        if (!isLoadingModelSettings) {
                            isLoadingModelSettings = true
                            scope.launch {
                                runCatching {
                                    Triple(
                                        networkClient.getModelProfileConfigurations(),
                                        networkClient.getMachineResourceConfiguration(),
                                        networkClient.getModelProviderCatalog(),
                                    )
                                }.onSuccess { (profiles, resources, providers) ->
                                        modelProfileConfigurations = profiles
                                        machineResourceConfiguration = resources
                                        modelProviderCatalog = providers
                                        modelProviderInspections = emptyList()
                                        showModelSettings = true
                                    }
                                    .onFailure { requestError = it.message ?: "Unable to load model settings." }
                                isLoadingModelSettings = false
                            }
                        }
                    },
                    onPlanScope = { planningScopeId = it },
                )
                Divider(modifier = Modifier.fillMaxHeight().width(1.dp), color = OrchardColors.divider)
                ArchitectPanel(
                    prompt = prompt,
                    message = requestError ?: snapshot.message,
                    isSubmitting = isSubmitting,
                    onPromptChange = { prompt = it },
                    onSubmit = ::submitPrompt,
                )
            }
            snapshot.entities.firstOrNull { it.id == definingWorkItemId }?.let { workItem ->
                val latestProposal = snapshot.definitionProposals
                    .filter { it.proposal.workItemId == workItem.id }
                    .maxByOrNull { it.proposal.revision }
                WorkDefinitionDialog(
                    workItem = workItem,
                    current = snapshot.workDefinitions.firstOrNull { it.workItemId == workItem.id },
                    latestProposal = latestProposal,
                    modelProfile = latestProposal?.proposal?.provenance?.bindingFingerprint?.let { fingerprint ->
                        snapshot.modelProfiles.firstOrNull { it.bindingFingerprint == fingerprint }
                    },
                    isSubmitting = isSubmittingDefinition,
                    isAnalyzing = workItem.id in analyzingWorkItemIds,
                    onDismiss = { if (!isSubmittingDefinition) definingWorkItemId = 0 },
                    onAnalyze = {
                        if (workItem.id !in analyzingWorkItemIds) {
                            analyzingWorkItemIds = analyzingWorkItemIds + workItem.id
                            scope.launch {
                                generateDefinitionProposal(workItem.id)
                                    .onFailure { requestError = it.message ?: "The local model could not revise the proposal." }
                                analyzingWorkItemIds = analyzingWorkItemIds - workItem.id
                            }
                        }
                    },
                    onFeedback = { proposalId, feedback ->
                        if (!isSubmittingDefinition) {
                            isSubmittingDefinition = true
                            scope.launch {
                                submitDefinitionFeedback(proposalId, feedback)
                                    .onFailure { requestError = it.message ?: "The feedback could not be recorded." }
                                isSubmittingDefinition = false
                            }
                        }
                    },
                    onSubmit = { proposalId, definition ->
                        if (!isSubmittingDefinition) {
                            isSubmittingDefinition = true
                            requestError = null
                            scope.launch {
                                val operation = if (proposalId == null) {
                                    submitWorkDefinition(workItem.id, definition)
                                } else {
                                    acceptDefinitionProposal(proposalId, definition)
                                }
                                operation
                                    .onSuccess { definingWorkItemId = 0 }
                                    .onFailure { requestError = it.message ?: "Unable to assess the work definition." }
                                isSubmittingDefinition = false
                            }
                        }
                    },
                )
            }
            snapshot.entities.firstOrNull { it.id == planningScopeId }?.let { scopeEntity ->
                                val memberTypes = if (scopeEntity.type == EPIC) setOf(STORY) else setOf(TASK, BUG)
                                val members = snapshot.entities.filter { it.parentId == scopeEntity.id && it.type in memberTypes }
                                val currentPlan = snapshot.stagedPlans.firstOrNull { it.plan.scopeId == scopeEntity.id }
                                val proposal = snapshot.circuitProposals
                                    .filter {
                                        it.proposal.scopeId == scopeEntity.id && it.acceptedPlanId == null &&
                                            it.proposal.content.plan.baseRevision == (currentPlan?.plan?.revision ?: 0) &&
                                            it.proposal.content.plan.baseHash == currentPlan?.plan?.hash
                                    }
                                    .maxByOrNull { it.proposal.revision }
                                StagedPlanDialog(
                                    scope = scopeEntity,
                                    members = members,
                                    current = currentPlan,
                                    proposal = proposal,
                                    workflows = snapshot.stageWorkflows,
                                    isSaving = isSavingStagedPlan,
                                    isGenerating = isGeneratingCircuit,
                                    onDismiss = { if (!isSavingStagedPlan && !isGeneratingCircuit) planningScopeId = 0 },
                                    onGenerate = {
                                        if (!isGeneratingCircuit) {
                                            isGeneratingCircuit = true
                                            requestError = null
                                            scope.launch {
                                                executeRequest { networkClient.generateCircuitProposal(scopeEntity.id) }
                                                    .onFailure {
                                                        requestError = it.message ?: "The Architect could not synthesize a circuit proposal."
                                                    }
                                                isGeneratingCircuit = false
                                            }
                                        }
                                    },
                                    onSave = { plan ->
                                        isSavingStagedPlan = true
                                        requestError = null
                                        scope.launch {
                                            executeRequest { networkClient.acceptStagedPlan(plan) }
                                                .onSuccess { planningScopeId = 0 }
                                                .onFailure { requestError = it.message ?: "Unable to accept the staged delivery circuit." }
                                            isSavingStagedPlan = false
                                        }
                                    },
                                )
                            }
            if (showModelSettings) {
                val resourceConfiguration = machineResourceConfiguration
                val providerCatalog = modelProviderCatalog
                modelProfileConfigurations.firstOrNull()?.let { configuration ->
                    if (resourceConfiguration == null || providerCatalog == null) return@let
                    ModelProfileSettingsDialog(
                        configuration = configuration,
                        resourceConfiguration = resourceConfiguration,
                        providerCatalog = providerCatalog,
                        providerInspections = modelProviderInspections,
                        isSaving = isSavingModelSettings,
                        isSavingMachinePolicy = isSavingMachinePolicy,
                        isSavingModelProvider = isSavingModelProvider,
                        isInspectingModelProvider = isInspectingModelProvider,
                        onDismiss = {
                            if (!isSavingModelSettings && !isSavingMachinePolicy && !isSavingModelProvider) showModelSettings = false
                        },
                        onInspectModelProvider = {
                            isInspectingModelProvider = true
                            requestError = null
                            scope.launch {
                                runCatching { networkClient.inspectModelProviders() }
                                    .onSuccess { modelProviderInspections = it }
                                    .onFailure { requestError = it.message ?: "Unable to inspect model providers." }
                                isInspectingModelProvider = false
                            }
                        },
                        onSaveModelProvider = { catalog ->
                            isSavingModelProvider = true
                            requestError = null
                            scope.launch {
                                runCatching { networkClient.updateModelProviderCatalog(catalog) }
                                    .onSuccess {
                                        modelProviderCatalog = it
                                        modelProfileConfigurations = networkClient.getModelProfileConfigurations()
                                        modelProviderInspections = emptyList()
                                    }
                                    .onFailure { requestError = it.message ?: "Unable to save model provider." }
                                isSavingModelProvider = false
                            }
                        },
                        onSaveMachinePolicy = { policy ->
                            isSavingMachinePolicy = true
                            requestError = null
                            scope.launch {
                                runCatching { networkClient.updateMachineUsagePolicy(policy) }
                                    .onSuccess { machineResourceConfiguration = it }
                                    .onFailure { requestError = it.message ?: "Unable to save machine usage policy." }
                                isSavingMachinePolicy = false
                            }
                        },
                        onSave = { override ->
                            isSavingModelSettings = true
                            requestError = null
                            scope.launch {
                                runCatching { networkClient.updateModelProfile(override) }
                                    .onSuccess {
                                        modelProfileConfigurations = it
                                        showModelSettings = false
                                    }
                                    .onFailure { requestError = it.message ?: "Unable to save model settings." }
                                isSavingModelSettings = false
                            }
                        },
                    )
                }
            }
        }
    }

    private suspend fun refreshWorkspace(): Result<Unit> {
        val sequence = requestSequence.incrementAndGet()
        return runCatching { networkClient.getCompanyState() }.map { envelope ->
            responseMutex.withLock {
                if (sequence > appliedResponseSequence) {
                    response = envelope.workspace
                    companyProjects = envelope.companyProjects
                    executionPlans = envelope.executionPlans
                    appliedResponseSequence = sequence
                }
            }
        }
    }

    private suspend fun sendArchitectPrompt(prompt: String): Result<Unit> = executeRequest {
        networkClient.submitArchitectPrompt(prompt)
    }

    private suspend fun advanceProjectGenesis(
        projectId: Int,
        submission: ProjectGenesisSubmissionRequest,
    ): Result<Unit> = executeRequest {
        networkClient.advanceProjectGenesis(projectId, submission)
    }

    private suspend fun admitProjectGenesis(projectId: Int): Result<Unit> = executeRequest {
        networkClient.admitProjectGenesis(projectId)
    }

    private suspend fun startCompany(projectId: Int): Result<Unit> = executeCompanyRequest {
        networkClient.startCompany(projectId)
    }

    private suspend fun promoteCompanyRun(runId: Long): Result<Unit> = executeCompanyRequest {
        networkClient.promoteCompanyRun(runId)
    }

    private suspend fun bindRepository(projectId: Int, path: String): Result<Unit> = executeRequest {
        networkClient.bindRepository(projectId, path)
    }

    private suspend fun startWorkflow(workItemId: Int): Result<Unit> = executeRequest {
        networkClient.startWorkflow(workItemId)
    }

    private suspend fun submitWorkDefinition(
        workItemId: Int,
        definition: WorkDefinitionSubmissionRequest,
    ): Result<Unit> = executeRequest {
        networkClient.submitWorkDefinition(workItemId, definition)
    }

    private suspend fun generateDefinitionProposal(workItemId: Int): Result<Unit> = executeRequest {
        networkClient.generateDefinitionProposal(workItemId)
    }

    private suspend fun submitDefinitionFeedback(proposalId: Long, content: String): Result<Unit> = executeRequest {
        networkClient.submitDefinitionFeedback(proposalId, content)
    }

    private suspend fun acceptDefinitionProposal(
        proposalId: Long,
        definition: WorkDefinitionSubmissionRequest,
    ): Result<Unit> = executeRequest {
        networkClient.acceptDefinitionProposal(proposalId, definition)
    }

    private suspend fun cancelWorkflow(runId: Long): Result<Unit> = executeRequest {
        networkClient.cancelWorkflow(runId)
    }

    private suspend fun executeRequest(request: suspend () -> WorkspaceSnapshotResponse): Result<Unit> {
        val sequence = requestSequence.incrementAndGet()
        return runCatching { request() }.map { snapshot ->
            responseMutex.withLock {
                if (sequence > appliedResponseSequence) {
                    response = snapshot
                    appliedResponseSequence = sequence
                }
            }
        }
    }

    private suspend fun executeCompanyRequest(
        request: suspend () -> com.orchard.frontend.network.CompanyWorkspaceResponse,
    ): Result<Unit> {
        val sequence = requestSequence.incrementAndGet()
        return runCatching { request() }.map { envelope ->
            responseMutex.withLock {
                if (sequence > appliedResponseSequence) {
                    response = envelope.workspace
                    companyProjects = envelope.companyProjects
                    executionPlans = envelope.executionPlans
                    appliedResponseSequence = sequence
                }
            }
        }
    }

    private fun readWorkspaceSnapshot(response: WorkspaceSnapshotResponse): WorkspaceSnapshot {
        val entities = ArrayList<WorkspaceEntity>()
        var focusId = 0
        var message = "Describe what you want to build."

        response.resources.values.forEach { resource ->
            when (resource.type) {
                "FOCUS" -> focusId = resource.path.toIntOrNull() ?: 0
                "MESSAGE" -> message = resource.path
                PROJECT, EPIC, STORY, TASK, BUG -> {
                    entities += WorkspaceEntity(
                        id = actionValue(resource.action, "id"),
                        type = resource.type,
                        title = resource.path,
                        parentId = actionValue(resource.action, "parent"),
                        status = actionValue(resource.action, "status").coerceIn(0, 3),
                    )
                }
            }
        }
        return WorkspaceSnapshot(
            entities,
            focusId,
            message,
            response.repositories,
            response.workflowRuns,
            response.workDefinitions,
            response.definitionProposals,
            response.stagedPlans,
            response.circuitProposals,
            response.stageWorkflows,
            response.circuitDispatches,
            response.modelProfiles,
        )
    }

    private fun actionValue(action: String, key: String): Int {
        val marker = "$key="
        val start = action.indexOf(marker)
        if (start == -1) return 0
        val valueStart = start + marker.length
        val valueEnd = action.indexOf(';', valueStart).let { if (it == -1) action.length else it }
        return action.substring(valueStart, valueEnd).toIntOrNull() ?: 0
    }

    private fun projectForFocus(snapshot: WorkspaceSnapshot): Int? {
        var entity = snapshot.entities.firstOrNull { it.id == snapshot.focusId } ?: return null
        while (entity.type != PROJECT) {
            entity = snapshot.entities.firstOrNull { it.id == entity.parentId } ?: return null
        }
        return entity.id
    }

    private fun epicForFocus(snapshot: WorkspaceSnapshot): Int? {
        var entity = snapshot.entities.firstOrNull { it.id == snapshot.focusId } ?: return null
        while (entity.type != EPIC) {
            entity = snapshot.entities.firstOrNull { it.id == entity.parentId } ?: return null
        }
        return entity.id
    }
}

private object OrchardColors {
    val canvas = Color(0xFFF4F1EC)
    val sidebar = Color(0xFFEEE9E2)
    val surface = Color(0xFFFCFBF8)
    val storyBand = Color(0xFFF0ECE5)
    val divider = Color(0xFFD9D2C8)
    val ink = Color(0xFF22231F)
    val muted = Color(0xFF72736D)
    val moss = Color(0xFF71804A)
    val mossSoft = Color(0xFFDDE3CC)
    val clay = Color(0xFFC4664E)
    val white = Color(0xFFFFFFFF)
}

@Composable
private fun OrchardTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = MaterialTheme.colors.copy(
            primary = OrchardColors.moss,
            background = OrchardColors.canvas,
            surface = OrchardColors.surface,
            onSurface = OrchardColors.ink,
        ),
        content = content,
    )
}

@Composable
private fun ProjectSidebar(
    projects: List<WorkspaceEntity>,
    selectedProjectId: Int,
    onSelect: (Int) -> Unit,
) {
    Column(
        modifier = Modifier.width(224.dp).fillMaxHeight().background(OrchardColors.sidebar).padding(20.dp),
    ) {
        Text("ORCHARD", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black, fontSize = 18.sp)
        Text("Project Center", color = OrchardColors.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 30.dp))
        Text("PROJECTS", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = OrchardColors.muted)
        Spacer(Modifier.height(10.dp))
        if (projects.isEmpty()) {
            Text("Your first project will appear here.", color = OrchardColors.muted, fontSize = 13.sp)
        }
        projects.forEach { project ->
            val selected = project.id == selectedProjectId
            TextButton(
                onClick = { onSelect(project.id) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                colors = ButtonDefaults.textButtonColors(backgroundColor = if (selected) OrchardColors.mossSoft else Color.Transparent),
                shape = RoundedCornerShape(6.dp),
            ) {
                Text(
                    project.title,
                    modifier = Modifier.fillMaxWidth(),
                    color = if (selected) OrchardColors.ink else OrchardColors.muted,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun WorkspaceBoard(
    modifier: Modifier,
    snapshot: WorkspaceSnapshot,
    projectId: Int,
    epics: List<WorkspaceEntity>,
    epicId: Int,
    repository: RepositoryResponse?,
    isBindingRepository: Boolean,
    startingWorkItemId: Int,
    cancellingRunId: Long,
    analyzingWorkItemIds: Set<Int>,
    onSelectEpic: (Int) -> Unit,
    onBindRepository: () -> Unit,
    onStartWorkflow: (Int) -> Unit,
    onCancelWorkflow: (Long) -> Unit,
    onDefineWork: (Int) -> Unit,
    onAnalyzeWork: (Int) -> Unit,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onPlanScope: (Int) -> Unit,
) {
    val project = snapshot.entities.firstOrNull { it.id == projectId }
    val stories = snapshot.entities.filter { it.type == STORY && it.parentId == epicId }
    Column(modifier = modifier.fillMaxHeight().background(OrchardColors.surface)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 22.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(project?.title ?: "Workspace", fontSize = 27.sp, fontWeight = FontWeight.Black, color = OrchardColors.ink)
                Text("AI-governed delivery backlog", fontSize = 13.sp, color = OrchardColors.muted)
            }
            Row {
                TextButton(onClick = { if (epicId != 0) onPlanScope(epicId) }, enabled = epicId != 0) {
                    Icon(Icons.Default.Edit, contentDescription = null, tint = OrchardColors.moss, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("Plan epic circuit", color = OrchardColors.moss, fontSize = 11.sp)
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "Model profile settings", tint = OrchardColors.muted)
                }
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh workspace", tint = OrchardColors.muted)
                }
            }
        }
        Divider(color = OrchardColors.divider)
        RepositoryBar(
            repository = repository,
            hasProject = project != null,
            isBinding = isBindingRepository,
            onBind = onBindRepository,
        )
        Divider(color = OrchardColors.divider)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 32.dp),
        ) {
            epics.forEach { epic ->
                val selected = epic.id == epicId
                Column(
                    modifier = Modifier.padding(end = 22.dp).clickable { onSelectEpic(epic.id) }.padding(top = 18.dp),
                ) {
                    Text(epic.title, color = if (selected) OrchardColors.ink else OrchardColors.muted, fontWeight = FontWeight.SemiBold)
                    Box(
                        Modifier.padding(top = 12.dp).height(3.dp).fillMaxWidth()
                            .background(if (selected) OrchardColors.moss else Color.Transparent),
                    )
                }
            }
        }
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            snapshot.stagedPlans.firstOrNull { it.plan.scopeId == epicId }?.let { plan ->
                StagedCircuitView(plan, snapshot.entities, snapshot.circuitDispatches)
            }
            when {
                project == null -> EmptyWorkspace("Create a project from the Architect panel.")
                epics.isEmpty() -> EmptyWorkspace("Create an epic inside ${project.title}.")
                stories.isEmpty() -> EmptyWorkspace("Create a story inside the selected epic.")
                else -> stories.forEach { story ->
                    StoryBoard(
                        story = story,
                        tickets = snapshot.entities.filter { it.parentId == story.id && (it.type == TASK || it.type == BUG) },
                        workflowRuns = snapshot.workflowRuns,
                        workDefinitions = snapshot.workDefinitions,
                        definitionProposals = snapshot.definitionProposals,
                        stagedPlan = snapshot.stagedPlans.firstOrNull { it.plan.scopeId == story.id },
                        circuitDispatches = snapshot.circuitDispatches,
                        startingWorkItemId = startingWorkItemId,
                        onStartWorkflow = onStartWorkflow,
                        cancellingRunId = cancellingRunId,
                        onCancelWorkflow = onCancelWorkflow,
                        onDefineWork = onDefineWork,
                        analyzingWorkItemIds = analyzingWorkItemIds,
                        onAnalyzeWork = onAnalyzeWork,
                        onPlanScope = onPlanScope,
                    )
                }
            }
        }
    }
}

@Composable
private fun RepositoryBar(
    repository: RepositoryResponse?,
    hasProject: Boolean,
    isBinding: Boolean,
    onBind: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().background(OrchardColors.canvas).padding(horizontal = 32.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 20.dp)) {
            Text("REPOSITORY", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp, color = OrchardColors.muted)
            if (repository == null) {
                Text("No local repository bound", color = OrchardColors.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            } else {
                Text(
                    repository.path,
                    color = OrchardColors.ink,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
                val details = if (repository.available) {
                    listOf(
                        repository.branch,
                        repository.buildSystem,
                        if (repository.dirty) "Uncommitted changes" else "Clean",
                        repository.remote.takeIf { it.isNotBlank() },
                    ).filterNotNull().joinToString("  |  ")
                } else {
                    "Repository unavailable"
                }
                Text(details, color = if (repository.available) OrchardColors.muted else OrchardColors.clay, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        TextButton(onClick = onBind, enabled = hasProject && !isBinding) {
            if (isBinding) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = OrchardColors.moss)
                Spacer(Modifier.width(7.dp))
                Text("Binding", color = OrchardColors.moss)
            } else {
                Icon(Icons.Default.FolderOpen, contentDescription = null, tint = OrchardColors.moss, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(7.dp))
                Text(if (repository == null) "Bind repository" else "Change repository", color = OrchardColors.moss)
            }
        }
    }
}

private fun chooseRepositoryDirectory(currentPath: String?): String? {
    val chooser = JFileChooser().apply {
        dialogTitle = "Bind local Git repository"
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        isAcceptAllFileFilterUsed = false
        currentPath?.let { currentDirectory = File(it) }
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile.absolutePath
    } else {
        null
    }
}

@Composable
private fun EmptyWorkspace(message: String) {
    Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
        Text(message, color = OrchardColors.muted)
    }
}

private data class PlanNodeDraft(
    val workItemId: Int,
    val nodeId: String,
    val stage: String,
    val dependsOn: String,
    val produces: String,
    val consumes: String,
)

@Composable
private fun StagedCircuitView(
    view: StagedDeliveryPlanViewResponse,
    entities: List<WorkspaceEntity>,
    dispatches: List<CircuitDispatchViewResponse>,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(view.plan.title, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = OrchardColors.ink)
            Text(
                "r${view.plan.revision} · ${view.plan.hash.take(8)}",
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                color = OrchardColors.muted,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            view.plan.stages.forEach { stage ->
                Column(Modifier.width(220.dp)) {
                    Text(
                        "STAGE ${stage.ordinal} · ${stage.title.uppercase()}",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp,
                        color = OrchardColors.moss,
                    )
                    Text(
                        "${stage.executionWorkflowId}@${stage.executionWorkflowVersion}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        color = OrchardColors.muted,
                    )
                    stage.nodes.forEach { node ->
                        val nodeView = view.nodes.first { it.node.nodeId == node.nodeId }
                        val dispatch = dispatches.lastOrNull {
                            it.dispatch.planId == view.plan.planId && it.dispatch.nodeId == node.nodeId
                        }
                        val title = entities.firstOrNull { it.id == node.workItemId }?.title ?: "Item ${node.workItemId}"
                        val stateColor = when (nodeView.state) {
                            "ELIGIBLE", "DONE" -> OrchardColors.moss
                            "RUNNING" -> OrchardColors.ink
                            else -> OrchardColors.clay
                        }
                        Column(
                            Modifier.fillMaxWidth().padding(top = 7.dp)
                                .background(OrchardColors.canvas, RoundedCornerShape(6.dp)).padding(9.dp),
                        ) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(node.label, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp, color = stateColor)
                                Text(nodeView.state.replace('_', ' '), fontFamily = FontFamily.Monospace, fontSize = 8.sp, color = stateColor)
                            }
                            Text(title, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = OrchardColors.ink)
                            dispatch?.let {
                                Text(
                                    "Q${it.dispatch.priority} · ${if (it.dispatch.integrationOwner) "INTEGRATION OWNER" else it.state}",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 8.sp,
                                    color = OrchardColors.muted,
                                )
                            }
                            if (nodeView.blockedReason.isNotBlank()) {
                                Text(nodeView.blockedReason, fontSize = 9.sp, color = OrchardColors.muted)
                            }
                            node.produces.forEach { artifact ->
                                Text("OUT ${artifact.kind} · ${artifact.name}", fontFamily = FontFamily.Monospace, fontSize = 8.sp, color = OrchardColors.moss)
                            }
                            node.consumes.forEach { artifact ->
                                Text("IN ${artifact.producerNodeId}:${artifact.kind}", fontFamily = FontFamily.Monospace, fontSize = 8.sp, color = OrchardColors.muted)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StagedPlanDialog(
    scope: WorkspaceEntity,
    members: List<WorkspaceEntity>,
    current: StagedDeliveryPlanViewResponse?,
    proposal: CircuitProposalViewResponse?,
    workflows: List<StageExecutionWorkflowDefinitionResponse>,
    isSaving: Boolean,
    isGenerating: Boolean,
    onDismiss: () -> Unit,
    onGenerate: () -> Unit,
    onSave: (StagedDeliveryPlanSubmissionRequest) -> Unit,
) {
    val proposedPlan = proposal?.proposal?.content?.plan
    var title by remember(scope.id, current?.plan?.hash, proposal?.proposal?.hash) {
        mutableStateOf(proposedPlan?.title ?: current?.plan?.title ?: "${scope.title} delivery circuit")
    }
    var drafts by remember(scope.id, current?.plan?.hash, proposal?.proposal?.hash, members.map { it.id }) {
        mutableStateOf(
            members.mapIndexed { index, member ->
                val proposed = proposedPlan?.stages.orEmpty().flatMapIndexed { stageIndex, stage ->
                    stage.nodes.map { stageIndex + 1 to it }
                }.firstOrNull { it.second.workItemId == member.id }
                val existing = current?.plan?.stages.orEmpty().flatMap { stage ->
                    stage.nodes.map { stage.ordinal to it }
                }.firstOrNull { it.second.workItemId == member.id }
                PlanNodeDraft(
                    workItemId = member.id,
                    nodeId = proposed?.second?.nodeId ?: existing?.second?.nodeId ?: "item-${member.id}",
                    stage = (proposed?.first ?: existing?.first)?.toString() ?: "1",
                    dependsOn = (proposed?.second?.dependsOn ?: existing?.second?.dependsOn).orEmpty().joinToString(","),
                    produces = (proposed?.second?.produces ?: existing?.second?.produces).orEmpty().joinToString(",") {
                        "${it.kind}:${it.name}:${it.evidenceKind}"
                    },
                    consumes = (proposed?.second?.consumes ?: existing?.second?.consumes).orEmpty()
                        .joinToString(",") { "${it.producerNodeId}:${it.kind}" },
                )
            }
        )
    }
    var workflowByStage by remember(scope.id, current?.plan?.hash, proposal?.proposal?.hash) {
        val sourceStages = proposedPlan?.stages?.mapIndexed { index, stage ->
            Triple(index + 1, stage.executionWorkflowId, stage.executionWorkflowVersion)
        } ?: current?.plan?.stages.orEmpty().map { stage ->
            Triple(stage.ordinal, stage.executionWorkflowId, stage.executionWorkflowVersion)
        }
        mutableStateOf(
            sourceStages.associate { (ordinal, workflowId, workflowVersion) ->
                ordinal to workflows.firstOrNull {
                    it.id == workflowId && it.version == workflowVersion
                }
            }.filterValues { it != null }.mapValues { requireNotNull(it.value) }
        )
    }
    var selectingWorkflowFor by remember { mutableStateOf<Int?>(null) }
    val parsed = drafts.map { draft -> draft.stage.toIntOrNull() to draft }
    val stages = parsed.mapNotNull { it.first }.toSortedSet()
    val nodeIds = drafts.map { it.nodeId.trim() }
    val stageByNode = parsed.mapNotNull { (stage, draft) -> stage?.let { draft.nodeId.trim() to it } }.toMap()
    val validStages = stages.isNotEmpty() && stages.toList() == (1..stages.last()).toList()
    val validNodes = drafts.isNotEmpty() && nodeIds.distinct().size == nodeIds.size && nodeIds.all { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) }
    val validDependencies = parsed.all { (stage, draft) ->
        val dependencies = csv(draft.dependsOn)
        stage != null && dependencies.distinct().size == dependencies.size && dependencies.all { it in stageByNode } &&
            (dependencies.mapNotNull(stageByNode::get).maxOrNull()?.plus(1) ?: 1) == stage
    }
    val validArtifacts = drafts.all { parseProducedArtifacts(it.produces) != null && parseConsumedArtifacts(it.consumes) != null }
    val valid = title.isNotBlank() && validStages && validNodes && validDependencies && validArtifacts &&
        stages.all { workflowByStage[it] != null }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Staged delivery circuit", fontWeight = FontWeight.Bold)
                Text(scope.title, fontSize = 11.sp, color = OrchardColors.muted)
            }
        },
        text = {
            Column(
                Modifier.width(760.dp).heightIn(max = 680.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Circuit title") },
                    enabled = !isSaving && !isGenerating,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                proposal?.proposal?.content?.let { content ->
                    Surface(
                        color = OrchardColors.storyBand,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "ARCHITECT PROPOSAL R${proposal.proposal.revision}",
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                color = OrchardColors.moss,
                            )
                            content.observations.forEach { Text("Observed: $it", fontSize = 11.sp, color = OrchardColors.ink) }
                            content.assumptions.forEach { Text("Assumption: $it", fontSize = 11.sp, color = OrchardColors.clay) }
                        }
                    }
                }
                stages.forEach { ordinal ->
                    val workflow = workflowByStage[ordinal]
                    Box {
                        TextButton(
                            onClick = { selectingWorkflowFor = ordinal },
                            enabled = !isSaving && !isGenerating && workflows.isNotEmpty(),
                        ) {
                            Text("Stage $ordinal workflow: ${workflow?.id ?: "Select"}@${workflow?.version ?: "-"}")
                        }
                        DropdownMenu(
                            expanded = selectingWorkflowFor == ordinal,
                            onDismissRequest = { selectingWorkflowFor = null },
                        ) {
                            workflows.forEach { candidate ->
                                DropdownMenuItem(onClick = {
                                    workflowByStage = workflowByStage + (ordinal to candidate)
                                    selectingWorkflowFor = null
                                }) {
                                    Column {
                                        Text("${candidate.id}@${candidate.version}")
                                        Text(candidate.description, fontSize = 11.sp, color = OrchardColors.muted)
                                    }
                                }
                            }
                        }
                    }
                }
                members.forEachIndexed { index, member ->
                    val draft = drafts[index]
                    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                        Text(member.title, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = OrchardColors.ink)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = draft.stage,
                                onValueChange = { value -> drafts = drafts.toMutableList().also { it[index] = draft.copy(stage = value.filter(Char::isDigit)) } },
                                label = { Text("Stage") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                enabled = !isSaving && !isGenerating,
                                singleLine = true,
                                modifier = Modifier.width(90.dp),
                            )
                            OutlinedTextField(
                                value = draft.nodeId,
                                onValueChange = { value -> drafts = drafts.toMutableList().also { it[index] = draft.copy(nodeId = value) } },
                                label = { Text("Node ID") },
                                enabled = !isSaving && !isGenerating,
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = draft.dependsOn,
                                onValueChange = { value -> drafts = drafts.toMutableList().also { it[index] = draft.copy(dependsOn = value) } },
                                label = { Text("Dependencies") },
                                enabled = !isSaving && !isGenerating,
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (scope.type == STORY) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = draft.produces,
                                    onValueChange = { value -> drafts = drafts.toMutableList().also { it[index] = draft.copy(produces = value) } },
                                    label = { Text("Outputs") },
                                    enabled = !isSaving && !isGenerating,
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                                OutlinedTextField(
                                    value = draft.consumes,
                                    onValueChange = { value -> drafts = drafts.toMutableList().also { it[index] = draft.copy(consumes = value) } },
                                    label = { Text("Inputs") },
                                    enabled = !isSaving && !isGenerating,
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                    Divider(color = OrchardColors.divider)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid && !isSaving && !isGenerating,
                onClick = {
                    onSave(
                        StagedDeliveryPlanSubmissionRequest(
                            scope.id,
                            title.trim(),
                            parsed.groupBy { requireNotNull(it.first) }.toSortedMap().map { (ordinal, entries) ->
                                val existingStage = current?.plan?.stages?.firstOrNull { it.ordinal == ordinal }
                                val proposedStage = proposedPlan?.stages?.getOrNull(ordinal - 1)
                                val workflow = requireNotNull(workflowByStage[ordinal])
                                StagedPlanStageSubmissionRequest(
                                    stageId = proposedStage?.stageId ?: existingStage?.stageId ?: "stage-$ordinal",
                                    title = proposedStage?.title ?: existingStage?.title ?: "Stage $ordinal",
                                    executionWorkflowId = workflow.id,
                                    executionWorkflowVersion = workflow.version,
                                    nodes = entries.map { (_, draft) ->
                                        StagedPlanNodeSubmissionRequest(
                                            draft.nodeId.trim(),
                                            draft.workItemId,
                                            csv(draft.dependsOn),
                                            requireNotNull(parseConsumedArtifacts(draft.consumes)),
                                            requireNotNull(parseProducedArtifacts(draft.produces)),
                                        )
                                    },
                                )
                            },
                            baseRevision = current?.plan?.revision ?: 0,
                            baseHash = current?.plan?.hash,
                            sourceProposal = proposal?.proposal?.let {
                                CircuitProposalReferenceRequest(it.proposalId, it.hash)
                            },
                        )
                    )
                },
            ) {
                if (isSaving) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                else Text(if (proposal == null) "Accept circuit" else "Accept proposal")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onGenerate, enabled = !isSaving && !isGenerating) {
                    if (isGenerating) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    else Text(if (proposal == null) "Generate proposal" else "Regenerate")
                }
                TextButton(onClick = onDismiss, enabled = !isSaving && !isGenerating) { Text("Cancel") }
            }
        },
        shape = RoundedCornerShape(8.dp),
        backgroundColor = OrchardColors.surface,
    )
}

private fun csv(value: String): List<String> = value.split(',').map(String::trim).filter(String::isNotEmpty)

private fun parseProducedArtifacts(value: String): List<StagedPlanArtifactRequest>? = csv(value).map { token ->
    val parts = token.split(':', limit = 3).map(String::trim)
    if (parts.size !in 2..3 || parts.any(String::isBlank)) return null
    StagedPlanArtifactRequest(parts[0], parts[1], parts.getOrElse(2) { "SOURCE_DIFF" })
}

private fun parseConsumedArtifacts(value: String): List<StagedPlanArtifactRequirementRequest>? = csv(value).map { token ->
    val parts = token.split(':', limit = 2).map(String::trim)
    if (parts.size != 2 || parts.any(String::isBlank)) return null
    StagedPlanArtifactRequirementRequest(parts[0], parts[1])
}

@Composable
private fun StoryBoard(
    story: WorkspaceEntity,
    tickets: List<WorkspaceEntity>,
    workflowRuns: List<WorkflowRunResponse>,
    workDefinitions: List<WorkDefinitionResponse>,
    definitionProposals: List<DefinitionProposalViewResponse>,
    stagedPlan: StagedDeliveryPlanViewResponse?,
    circuitDispatches: List<CircuitDispatchViewResponse>,
    startingWorkItemId: Int,
    onStartWorkflow: (Int) -> Unit,
    cancellingRunId: Long,
    onCancelWorkflow: (Long) -> Unit,
    onDefineWork: (Int) -> Unit,
    analyzingWorkItemIds: Set<Int>,
    onAnalyzeWork: (Int) -> Unit,
    onPlanScope: (Int) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().background(OrchardColors.storyBand, RoundedCornerShape(8.dp)).padding(18.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(story.title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = OrchardColors.ink)
            TextButton(onClick = { onPlanScope(story.id) }) {
                Icon(Icons.Default.Edit, contentDescription = null, tint = OrchardColors.moss, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text("Plan circuit", color = OrchardColors.moss, fontSize = 10.sp)
            }
        }
        stagedPlan?.let { plan ->
            StagedCircuitView(plan, listOf(story) + tickets, circuitDispatches)
            Spacer(Modifier.height(14.dp))
        }
        Spacer(Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("Todo", "In progress", "In review", "Done").forEachIndexed { status, label ->
                Column(modifier = Modifier.weight(1f).widthIn(min = 120.dp)) {
                    val statusTickets = tickets.filter { it.status == status }
                    Text(label.uppercase(), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp, color = OrchardColors.muted)
                    Spacer(Modifier.height(9.dp))
                    statusTickets.forEach { ticket ->
                        TicketCard(
                            ticket = ticket,
                            workflowRun = workflowRuns.lastOrNull { it.context.workItemId == ticket.id },
                            workDefinition = workDefinitions.firstOrNull { it.workItemId == ticket.id },
                            latestProposal = definitionProposals.filter { it.proposal.workItemId == ticket.id }
                                .maxByOrNull { it.proposal.revision },
                            isStarting = startingWorkItemId == ticket.id,
                            onStartWorkflow = { onStartWorkflow(ticket.id) },
                            isCancelling = workflowRuns.lastOrNull { it.context.workItemId == ticket.id }?.runId == cancellingRunId,
                            onCancelWorkflow = { runId -> onCancelWorkflow(runId) },
                            onDefineWork = { onDefineWork(ticket.id) },
                            isAnalyzing = ticket.id in analyzingWorkItemIds,
                            onAnalyzeWork = { onAnalyzeWork(ticket.id) },
                            circuitManaged = stagedPlan?.nodes?.any { it.node.workItemId == ticket.id } == true,
                        )
                    }
                    if (statusTickets.isEmpty()) {
                        Box(Modifier.fillMaxWidth().height(54.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun TicketCard(
    ticket: WorkspaceEntity,
    workflowRun: WorkflowRunResponse?,
    workDefinition: WorkDefinitionResponse?,
    latestProposal: DefinitionProposalViewResponse?,
    isStarting: Boolean,
    onStartWorkflow: () -> Unit,
    isCancelling: Boolean,
    onCancelWorkflow: (Long) -> Unit,
    onDefineWork: () -> Unit,
    isAnalyzing: Boolean,
    onAnalyzeWork: () -> Unit,
    circuitManaged: Boolean,
) {
    Surface(
        color = OrchardColors.white,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, OrchardColors.divider),
        elevation = 1.dp,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    ) {
        Column(Modifier.padding(11.dp)) {
            Text(ticket.title, fontSize = 13.sp, lineHeight = 18.sp, color = OrchardColors.ink)
            Text(
                ticket.type,
                modifier = Modifier.padding(top = 10.dp),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp,
                color = if (ticket.type == BUG) OrchardColors.clay else OrchardColors.moss,
            )
            if (workflowRun == null) {
                Divider(color = OrchardColors.divider, modifier = Modifier.padding(top = 9.dp, bottom = 7.dp))
                val definitionState = workDefinition?.assessment?.status ?: "UNDEFINED"
                val definitionColor = if (definitionState == "READY") OrchardColors.moss else OrchardColors.clay
                Text(
                    "$definitionState${workDefinition?.let { "  R${it.revision}" }.orEmpty()}",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp,
                    color = definitionColor,
                )
                val outstanding = workDefinition?.assessment?.missingFields.orEmpty() +
                    workDefinition?.assessment?.ambiguities.orEmpty()
                if (outstanding.isNotEmpty()) {
                    Text(
                        outstanding.joinToString(", "),
                        color = OrchardColors.muted,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                latestProposal?.let { proposal ->
                    Text(
                        "${proposal.proposal.actor.replace('_', ' ')} PROPOSAL  R${proposal.proposal.revision}",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp,
                        color = OrchardColors.muted,
                        modifier = Modifier.padding(top = 5.dp),
                    )
                    proposal.feedback.lastOrNull()?.let { feedback ->
                        Text(
                            "Feedback: ${feedback.content}",
                            fontSize = 10.sp,
                            lineHeight = 14.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = OrchardColors.ink,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                }
                TextButton(
                    onClick = onAnalyzeWork,
                    enabled = !isAnalyzing,
                    modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 3.dp),
                ) {
                    if (isAnalyzing) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = OrchardColors.moss)
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null, tint = OrchardColors.moss, modifier = Modifier.size(15.dp))
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(if (latestProposal == null) "Analyze with local model" else "Ask model to revise", fontSize = 11.sp, color = OrchardColors.moss)
                }
                TextButton(
                    onClick = onDefineWork,
                    modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 3.dp),
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null, tint = definitionColor, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (workDefinition == null) "Define work" else "Revise definition", fontSize = 11.sp, color = definitionColor)
                }
                if (definitionState == "READY" && !circuitManaged) {
                TextButton(
                    onClick = onStartWorkflow,
                    enabled = !isStarting,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 3.dp),
                ) {
                    if (isStarting) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = OrchardColors.moss)
                        Spacer(Modifier.width(6.dp))
                        Text("Recalling context", fontSize = 11.sp, color = OrchardColors.moss)
                    } else {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = OrchardColors.moss, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Start workflow", fontSize = 11.sp, color = OrchardColors.moss)
                    }
                }
                }
            } else {
                Divider(color = OrchardColors.divider, modifier = Modifier.padding(vertical = 9.dp))
                val latestEvidence = workflowRun.evidence
                    .groupBy { it.kind }
                    .mapValues { (_, records) -> records.maxBy { it.evidenceId } }
                val passedGates = latestEvidence.values.count { it.passed }
                val stateColor = if (workflowRun.state == "EVIDENCE_BLOCKED") OrchardColors.clay else OrchardColors.moss
                Text(
                    "${workflowRun.state.replace('_', ' ')}  ${workflowRun.context.repository.commitHash.take(8)}",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp,
                    color = stateColor,
                )
                workflowRun.context.workspaceReservation?.let { reservation ->
                    Text(
                        "${reservation.mode} · ${reservation.branch}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 8.sp,
                        color = OrchardColors.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    "$passedGates/${workflowRun.workflow.evidenceContract.requirements.size} evidence gates passed",
                    fontSize = 10.sp,
                    color = OrchardColors.muted,
                    modifier = Modifier.padding(top = 4.dp),
                )
                workflowRun.workDefinition?.let { definition ->
                    Text(
                        "Definition R${definition.revision}  ${definition.hash.take(8)}",
                        fontSize = 10.sp,
                        color = OrchardColors.muted,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                workflowRun.context.recalledEpisodes.firstOrNull()?.let { recall ->
                    Text(
                        "Prior fix: ${recall.resolution}",
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        color = OrchardColors.ink,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 7.dp),
                    )
                }
                if (workflowRun.state != "DONE" && workflowRun.state != "CANCELLED") {
                    TextButton(
                        onClick = { onCancelWorkflow(workflowRun.runId) },
                        enabled = !isCancelling,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 3.dp),
                    ) {
                        if (isCancelling) {
                            CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp, color = OrchardColors.clay)
                        } else {
                            Icon(Icons.Default.Close, contentDescription = null, tint = OrchardColors.clay, modifier = Modifier.size(15.dp))
                        }
                        Spacer(Modifier.width(4.dp))
                        Text(if (isCancelling) "Cancelling" else "Cancel workflow", fontSize = 10.sp, color = OrchardColors.clay)
                    }
                }
                if (workflowRun.state == "CANCELLED" && circuitManaged) {
                    TextButton(
                        onClick = onStartWorkflow,
                        enabled = !isStarting,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 3.dp),
                    ) {
                        if (isStarting) {
                            CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp, color = OrchardColors.moss)
                        } else {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = OrchardColors.moss, modifier = Modifier.size(15.dp))
                        }
                        Spacer(Modifier.width(4.dp))
                        Text(if (isStarting) "Allocating replacement" else "Retry workflow", fontSize = 10.sp, color = OrchardColors.moss)
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelProfileSettingsDialog(
    configuration: ModelProfileConfigurationResponse,
    resourceConfiguration: MachineResourceConfigurationResponse,
    providerCatalog: ModelProviderCatalogResponse,
    providerInspections: List<ModelEndpointInspectionResponse>,
    isSaving: Boolean,
    isSavingMachinePolicy: Boolean,
    isSavingModelProvider: Boolean,
    isInspectingModelProvider: Boolean,
    onDismiss: () -> Unit,
    onInspectModelProvider: () -> Unit,
    onSaveModelProvider: (ModelProviderCatalogResponse) -> Unit,
    onSaveMachinePolicy: (MachineUsagePolicyRequest) -> Unit,
    onSave: (ModelProfileOverrideRequest) -> Unit,
) {
    val endpoint = providerCatalog.endpoints.first()
    val binding = providerCatalog.bindings.first()
    var providerPolicy by remember(providerCatalog.policy) { mutableStateOf(providerCatalog.policy) }
    var protocol by remember(endpoint.protocol) { mutableStateOf(endpoint.protocol) }
    var locality by remember(endpoint.locality) { mutableStateOf(endpoint.locality) }
    var displayName by remember(endpoint.displayName) { mutableStateOf(endpoint.displayName) }
    var baseUrl by remember(endpoint.baseUrl) { mutableStateOf(endpoint.baseUrl) }
    var credentialReference by remember(endpoint.credentialReference) { mutableStateOf(endpoint.credentialReference.orEmpty()) }
    var model by remember(binding.model) { mutableStateOf(binding.model) }
    var contextWindow by remember(binding.contextWindowTokens) { mutableStateOf(binding.contextWindowTokens.toString()) }
    var residentMemoryMiB by remember(binding.residentMemoryBytes) { mutableStateOf((binding.residentMemoryBytes / MEBIBYTE).toString()) }
    var providerCpuUnits by remember(binding.cpuUnits) { mutableStateOf(binding.cpuUnits.toString()) }
    var inputBudget by remember(configuration.effectiveProfile.inputBudgetTokens) {
        mutableStateOf(configuration.effectiveProfile.inputBudgetTokens.toString())
    }
    var outputBudget by remember(configuration.effectiveProfile.outputBudgetTokens) {
        mutableStateOf(configuration.effectiveProfile.outputBudgetTokens.toString())
    }
    var preferredBindingId by remember(configuration.override?.preferredBindingId) {
        mutableStateOf(configuration.override?.preferredBindingId)
    }
    var capacityPercent by remember(resourceConfiguration.policy.capacityPercent) {
        mutableStateOf(resourceConfiguration.policy.capacityPercent)
    }
    var minimumFreeMemoryMiB by remember(resourceConfiguration.policy.minimumFreeMemoryBytes) {
        mutableStateOf((resourceConfiguration.policy.minimumFreeMemoryBytes / MEBIBYTE).toString())
    }
    var maxConcurrentExecutions by remember(resourceConfiguration.policy.maxConcurrentModelExecutions) {
        mutableStateOf(resourceConfiguration.policy.maxConcurrentModelExecutions.toString())
    }
    val inputTokens = inputBudget.toIntOrNull()
    val outputTokens = outputBudget.toIntOrNull()
    val validNumbers = inputTokens != null && outputTokens != null && inputTokens >= 1_024 && outputTokens >= 256
    val requiredCapacity = if (validNumbers) requireNotNull(inputTokens).toLong() + requireNotNull(outputTokens).toLong() else Long.MAX_VALUE
    val compatibleDraftBindings = configuration.installedBindings.filter {
        it.contextWindowTokens.toLong() >= requiredCapacity &&
            it.capabilities.containsAll(configuration.effectiveProfile.requiredCapabilities)
    }
    val selectedBindingFits = if (preferredBindingId == null) {
        compatibleDraftBindings.isNotEmpty()
    } else {
        compatibleDraftBindings.any { it.bindingId == preferredBindingId }
    }
    val minimumFreeMiB = minimumFreeMemoryMiB.toLongOrNull()
    val minimumFreeBytes = minimumFreeMiB?.takeIf { it <= Long.MAX_VALUE / MEBIBYTE }?.times(MEBIBYTE)
    val maxConcurrent = maxConcurrentExecutions.toIntOrNull()
    val validResourcePolicy = minimumFreeBytes != null && minimumFreeBytes >= 0 &&
        minimumFreeBytes < resourceConfiguration.capacity.totalMemoryBytes &&
        maxConcurrent != null && maxConcurrent in 1..resourceConfiguration.capacity.logicalProcessors
    val providerContext = contextWindow.toIntOrNull()
    val providerMemoryMiB = residentMemoryMiB.toLongOrNull()
    val providerMemoryBytes = providerMemoryMiB?.takeIf { it <= Long.MAX_VALUE / MEBIBYTE }?.times(MEBIBYTE)
    val providerCpus = providerCpuUnits.toIntOrNull()
    val validCredentialReference = credentialReference.isBlank() || Regex("env:[A-Z][A-Z0-9_]{1,127}").matches(credentialReference)
    val validProvider = displayName.isNotBlank() && baseUrl.startsWith("http") && model.isNotBlank() &&
        providerContext != null && providerContext in 2_048..1_000_000 && providerMemoryBytes != null &&
        providerMemoryBytes >= 0 && providerCpus != null && providerCpus in 1..256 && validCredentialReference &&
        !(providerPolicy == "LOCAL_ONLY" && locality == "REMOTE") && !(locality == "LOCAL" && credentialReference.isNotBlank())
    val inspection = providerInspections.firstOrNull { it.endpointId == endpoint.endpointId }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Execution settings", fontWeight = FontWeight.Bold)
                Text(configuration.effectiveProfile.id, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = OrchardColors.muted)
            }
        },
        text = {
            Column(
                modifier = Modifier.width(520.dp).heightIn(max = 680.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("MODEL PROVIDER", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp, color = OrchardColors.moss)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SettingsChoice(
                        "Policy",
                        providerPolicy,
                        listOf("LOCAL_ONLY", "LOCAL_PREFERRED", "CLOUD_ALLOWED", "CLOUD_ESCALATION_ONLY"),
                        { providerPolicy = it },
                        !isSavingModelProvider,
                        Modifier.weight(1f),
                    )
                    SettingsChoice(
                        "Protocol",
                        protocol,
                        listOf("OLLAMA_NATIVE", "OPENAI_COMPATIBLE"),
                        { protocol = it },
                        !isSavingModelProvider,
                        Modifier.weight(1f),
                    )
                    SettingsChoice(
                        "Locality",
                        locality,
                        listOf("LOCAL", "REMOTE"),
                        {
                            locality = it
                            if (it == "LOCAL") credentialReference = ""
                        },
                        !isSavingModelProvider,
                        Modifier.weight(1f),
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = displayName,
                        onValueChange = { displayName = it },
                        enabled = !isSavingModelProvider,
                        label = { Text("Endpoint name") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        enabled = !isSavingModelProvider,
                        label = { Text("Base URL") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        enabled = !isSavingModelProvider,
                        label = { Text("Model") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = contextWindow,
                        onValueChange = { contextWindow = it.filter(Char::isDigit) },
                        enabled = !isSavingModelProvider,
                        label = { Text("Context tokens") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = residentMemoryMiB,
                        onValueChange = { residentMemoryMiB = it.filter(Char::isDigit) },
                        enabled = !isSavingModelProvider,
                        label = { Text("Resident memory (MiB)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = providerCpuUnits,
                        onValueChange = { providerCpuUnits = it.filter(Char::isDigit) },
                        enabled = !isSavingModelProvider,
                        label = { Text("CPU units") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    if (locality == "REMOTE") {
                        OutlinedTextField(
                            value = credentialReference,
                            onValueChange = {
                                credentialReference = if (it.startsWith("env:", ignoreCase = true)) {
                                    "env:${it.substringAfter(':').uppercase()}"
                                } else {
                                    it.uppercase()
                                }
                            },
                            enabled = !isSavingModelProvider,
                            label = { Text("Credential (env:NAME)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                inspection?.let {
                    Text(
                        if (it.reachable) "REACHABLE · ${it.discoveredModels.joinToString().ifBlank { "No models reported" }}"
                        else "UNREACHABLE · ${it.diagnostic.ifBlank { "No diagnostic" }}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = if (it.reachable) OrchardColors.moss else OrchardColors.clay,
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(enabled = !isInspectingModelProvider && !isSavingModelProvider, onClick = onInspectModelProvider) {
                        if (isInspectingModelProvider) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        else Text("Inspect endpoint")
                    }
                    TextButton(
                        enabled = validProvider && !isSavingModelProvider,
                        onClick = {
                            onSaveModelProvider(
                                providerCatalog.copy(
                                    policy = providerPolicy,
                                    endpoints = providerCatalog.endpoints.map {
                                        if (it.endpointId != endpoint.endpointId) it else endpoint.copy(
                                            displayName = displayName.trim(),
                                            protocol = protocol,
                                            baseUrl = baseUrl.trimEnd('/'),
                                            locality = locality,
                                            credentialReference = credentialReference.ifBlank { null },
                                        )
                                    },
                                    bindings = providerCatalog.bindings.map {
                                        if (it.bindingId != binding.bindingId) it else binding.copy(
                                            model = model.trim(),
                                            contextWindowTokens = requireNotNull(providerContext),
                                            residentMemoryBytes = requireNotNull(providerMemoryBytes),
                                            cpuUnits = requireNotNull(providerCpus),
                                        )
                                    },
                                )
                            )
                        },
                    ) {
                        if (isSavingModelProvider) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        else Text("Save provider")
                    }
                }
                Divider(color = OrchardColors.divider)
                Text("MACHINE CAPACITY", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp, color = OrchardColors.moss)
                Text(
                    "${formatGiB(resourceConfiguration.capacity.availableMemoryBytes)} available of " +
                        "${formatGiB(resourceConfiguration.capacity.totalMemoryBytes)} · " +
                        "${resourceConfiguration.capacity.systemCpuLoad?.let { "${(it * 100).roundToInt()}% CPU load" } ?: "CPU load unavailable"} · " +
                        "${resourceConfiguration.activeLeases} active leases",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = OrchardColors.muted,
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Orchard share", fontSize = 12.sp, modifier = Modifier.width(110.dp))
                    Slider(
                        value = capacityPercent.toFloat(),
                        onValueChange = { capacityPercent = it.roundToInt() },
                        valueRange = 1f..100f,
                        steps = 98,
                        enabled = !isSavingMachinePolicy,
                        modifier = Modifier.weight(1f),
                    )
                    Text("$capacityPercent%", fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.width(42.dp))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = minimumFreeMemoryMiB,
                        onValueChange = { minimumFreeMemoryMiB = it.filter(Char::isDigit) },
                        enabled = !isSavingMachinePolicy,
                        label = { Text("Minimum free memory (MiB)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = maxConcurrentExecutions,
                        onValueChange = { maxConcurrentExecutions = it.filter(Char::isDigit) },
                        enabled = !isSavingMachinePolicy,
                        label = { Text("Concurrent model jobs") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                resourceConfiguration.lastAdmission?.let { admission ->
                    Text(
                        "${admission.decision}: ${admission.reason}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = if (admission.decision == "ADMITTED") OrchardColors.moss else OrchardColors.clay,
                    )
                }
                TextButton(
                    enabled = validResourcePolicy && !isSavingMachinePolicy,
                    onClick = {
                        onSaveMachinePolicy(
                            MachineUsagePolicyRequest(
                                capacityPercent,
                                requireNotNull(minimumFreeBytes),
                                requireNotNull(maxConcurrent),
                            )
                        )
                    },
                    modifier = Modifier.align(Alignment.End),
                ) {
                    if (isSavingMachinePolicy) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    else Text("Save machine policy")
                }
                Divider(color = OrchardColors.divider)
                Text("MODEL APERTURE", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp, color = OrchardColors.moss)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = inputBudget,
                        onValueChange = { inputBudget = it.filter(Char::isDigit) },
                        enabled = !isSaving,
                        label = { Text("Input aperture (tokens)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = outputBudget,
                        onValueChange = { outputBudget = it.filter(Char::isDigit) },
                        enabled = !isSaving,
                        label = { Text("Output reserve (tokens)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    "Default ${configuration.defaultProfile.inputBudgetTokens} input + " +
                        "${configuration.defaultProfile.outputBudgetTokens} output",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = OrchardColors.muted,
                )
                Divider(color = OrchardColors.divider)
                Text("MODEL BINDING", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp, color = OrchardColors.moss)
                Row(
                    modifier = Modifier.fillMaxWidth().clickable(enabled = !isSaving) { preferredBindingId = null },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = preferredBindingId == null, onClick = { preferredBindingId = null }, enabled = !isSaving)
                    Text("Automatic routing", fontSize = 12.sp)
                }
                configuration.installedBindings.forEach { binding ->
                    val compatible = binding.contextWindowTokens.toLong() >= requiredCapacity &&
                        binding.capabilities.containsAll(configuration.effectiveProfile.requiredCapabilities)
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable(enabled = compatible && !isSaving) {
                            preferredBindingId = binding.bindingId
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = preferredBindingId == binding.bindingId,
                            onClick = { preferredBindingId = binding.bindingId },
                            enabled = compatible && !isSaving,
                        )
                        Column {
                            Text(binding.model, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text(
                                "${binding.provider} · ${binding.contextWindowTokens} token capacity",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = if (compatible) OrchardColors.muted else OrchardColors.clay,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = validNumbers && selectedBindingFits && !isSaving,
                onClick = {
                    onSave(
                        ModelProfileOverrideRequest(
                            profileId = configuration.effectiveProfile.id,
                            inputBudgetTokens = requireNotNull(inputTokens),
                            outputBudgetTokens = requireNotNull(outputTokens),
                            preferredBindingId = preferredBindingId,
                        )
                    )
                },
            ) {
                if (isSaving) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                else Text("Save profile")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isSaving) { Text("Cancel") } },
        shape = RoundedCornerShape(8.dp),
        backgroundColor = OrchardColors.surface,
    )
}

@Composable
private fun SettingsChoice(
    label: String,
    value: String,
    options: List<String>,
    onSelected: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(label, fontSize = 10.sp, color = OrchardColors.muted)
        Box {
            TextButton(onClick = { expanded = true }, enabled = enabled, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)) {
                Text(value, fontFamily = FontFamily.Monospace, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(onClick = {
                        onSelected(option)
                        expanded = false
                    }) { Text(option, fontFamily = FontFamily.Monospace, fontSize = 10.sp) }
                }
            }
        }
    }
}

private const val MEBIBYTE = 1_048_576L

private fun formatGiB(bytes: Long): String = "%.1f GiB".format(bytes.toDouble() / 1_073_741_824.0)

@Composable
private fun WorkDefinitionDialog(
    workItem: WorkspaceEntity,
    current: WorkDefinitionResponse?,
    latestProposal: DefinitionProposalViewResponse?,
    modelProfile: ModelCapabilityProfileResponse?,
    isSubmitting: Boolean,
    isAnalyzing: Boolean,
    onDismiss: () -> Unit,
    onAnalyze: () -> Unit,
    onFeedback: (Long, String) -> Unit,
    onSubmit: (Long?, WorkDefinitionSubmissionRequest) -> Unit,
) {
    val existing = latestProposal?.proposal?.content?.definition ?: current?.definition
    val revisionKey = latestProposal?.proposal?.hash ?: current?.hash
    var requestedOutcome by remember(workItem.id, revisionKey) { mutableStateOf(existing?.requestedOutcome.orEmpty()) }
    var currentBehavior by remember(workItem.id, revisionKey) { mutableStateOf(existing?.currentBehavior.orEmpty()) }
    var requiredBehavior by remember(workItem.id, revisionKey) { mutableStateOf(existing?.requiredBehavior.orEmpty()) }
    var scope by remember(workItem.id, revisionKey) { mutableStateOf(existing?.scope.orEmpty().joinToString("\n")) }
    var nonGoals by remember(workItem.id, revisionKey) { mutableStateOf(existing?.nonGoals.orEmpty().joinToString("\n")) }
    var constraints by remember(workItem.id, revisionKey) { mutableStateOf(existing?.constraints.orEmpty().joinToString("\n")) }
    var criteria by remember(workItem.id, revisionKey) {
        mutableStateOf(existing?.acceptanceCriteria.orEmpty().joinToString("\n") { it.description })
    }
    var verification by remember(workItem.id, revisionKey) {
        mutableStateOf(existing?.acceptanceCriteria.orEmpty().joinToString("\n") { it.verification })
    }
    var questions by remember(workItem.id, revisionKey) { mutableStateOf(existing?.unresolvedQuestions.orEmpty().joinToString("\n")) }
    var splits by remember(workItem.id, revisionKey) { mutableStateOf(existing?.proposedSplitTitles.orEmpty().joinToString("\n")) }
    var reproduction by remember(workItem.id, revisionKey) { mutableStateOf(existing?.reproduction.orEmpty()) }
    var regression by remember(workItem.id, revisionKey) { mutableStateOf(existing?.regressionCriterion.orEmpty()) }
    var feedback by remember(workItem.id, revisionKey) { mutableStateOf("") }

    fun lines(value: String): List<String> = value.lines().map(String::trim).filter(String::isNotEmpty)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Define ${workItem.type.lowercase()}", fontWeight = FontWeight.Bold)
                Text(workItem.title, fontSize = 12.sp, color = OrchardColors.muted)
            }
        },
        text = {
            Column(
                modifier = Modifier.width(620.dp).heightIn(max = 680.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DefinitionField("Requested outcome", requestedOutcome, !isAnalyzing) { requestedOutcome = it }
                DefinitionField("Current behavior", currentBehavior, !isAnalyzing) { currentBehavior = it }
                DefinitionField("Required behavior", requiredBehavior, !isAnalyzing) { requiredBehavior = it }
                DefinitionField("Scope (one per line)", scope, !isAnalyzing) { scope = it }
                DefinitionField("Non-goals (one per line)", nonGoals, !isAnalyzing) { nonGoals = it }
                DefinitionField("Constraints (one per line)", constraints, !isAnalyzing) { constraints = it }
                DefinitionField("Acceptance criteria (one per line)", criteria, !isAnalyzing) { criteria = it }
                DefinitionField("Verification for each criterion (one per line)", verification, !isAnalyzing) { verification = it }
                if (workItem.type == BUG) {
                    DefinitionField("Reproduction", reproduction, !isAnalyzing) { reproduction = it }
                    DefinitionField("Regression criterion", regression, !isAnalyzing) { regression = it }
                }
                DefinitionField("Unresolved questions (one per line)", questions, !isAnalyzing) { questions = it }
                DefinitionField("Proposed split titles (one per line)", splits, !isAnalyzing) { splits = it }
                latestProposal?.let { proposal ->
                    Divider(color = OrchardColors.divider, modifier = Modifier.padding(vertical = 4.dp))
                    modelProfile?.let { profile ->
                        Text(
                            "MODEL MEMORY  ${profile.binding.model}",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            color = OrchardColors.moss,
                        )
                        Text(
                            "${profile.executionProfileId} · ${profile.sampleCount} runs · " +
                                "${(profile.schemaValidityRate * 100).toInt()}% valid · " +
                                "${profile.acceptedUnchangedCount} unchanged · " +
                                "${profile.acceptedAfterEditCount} edited · " +
                                "${profile.revisionRequestedCount} revised · ${profile.medianLatencyMillis} ms median",
                            fontSize = 10.sp,
                            lineHeight = 14.sp,
                            color = OrchardColors.muted,
                        )
                    }
                    if (proposal.proposal.content.observations.isNotEmpty()) {
                        Text("OBSERVATIONS", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp, color = OrchardColors.moss)
                        Text(proposal.proposal.content.observations.joinToString("\n"), fontSize = 11.sp, lineHeight = 16.sp)
                    }
                    if (proposal.proposal.content.assumptions.isNotEmpty()) {
                        Text("ASSUMPTIONS", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp, color = OrchardColors.clay)
                        Text(proposal.proposal.content.assumptions.joinToString("\n"), fontSize = 11.sp, lineHeight = 16.sp)
                    }
                    DefinitionField("Human feedback for the next revision", feedback, !isAnalyzing) { feedback = it }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(
                            onClick = { onFeedback(proposal.proposal.proposalId, feedback) },
                            enabled = feedback.isNotBlank() && !isSubmitting && !isAnalyzing,
                        ) { Text("Record feedback") }
                        TextButton(onClick = onAnalyze, enabled = !isSubmitting && !isAnalyzing) {
                            if (isAnalyzing) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            else Text("Generate revision")
                        }
                    }
                } ?: TextButton(onClick = onAnalyze, enabled = !isSubmitting && !isAnalyzing) {
                    if (isAnalyzing) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    else Text("Analyze with local model")
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !isSubmitting && !isAnalyzing,
                onClick = {
                    val descriptions = lines(criteria)
                    val verifications = lines(verification)
                    onSubmit(
                        latestProposal?.proposal?.proposalId,
                        WorkDefinitionSubmissionRequest(
                            requestedOutcome = requestedOutcome,
                            currentBehavior = currentBehavior,
                            requiredBehavior = requiredBehavior,
                            scope = lines(scope),
                            nonGoals = lines(nonGoals),
                            constraints = lines(constraints),
                            acceptanceCriteria = descriptions.mapIndexed { index, description ->
                                AcceptanceCriterionRequest(description, verifications.getOrElse(index) { "" })
                            },
                            unresolvedQuestions = lines(questions),
                            proposedSplitTitles = lines(splits),
                            reproduction = reproduction,
                            regressionCriterion = regression,
                        )
                    )
                },
            ) {
                if (isSubmitting) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                else Text(if (latestProposal == null) "Assess human definition" else "Accept and assess")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isSubmitting && !isAnalyzing) { Text("Cancel") } },
        shape = RoundedCornerShape(8.dp),
        backgroundColor = OrchardColors.surface,
    )
}

@Composable
private fun DefinitionField(label: String, value: String, enabled: Boolean, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        label = { Text(label, fontSize = 11.sp) },
        modifier = Modifier.fillMaxWidth(),
        minLines = 2,
        maxLines = 4,
        colors = TextFieldDefaults.outlinedTextFieldColors(
            focusedBorderColor = OrchardColors.moss,
            unfocusedBorderColor = OrchardColors.divider,
            backgroundColor = OrchardColors.white,
        ),
    )
}

@Composable
private fun ArchitectPanel(
    prompt: String,
    message: String,
    isSubmitting: Boolean,
    onPromptChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Column(
        modifier = Modifier.width(352.dp).fillMaxHeight().background(OrchardColors.canvas),
    ) {
        Column(Modifier.padding(22.dp)) {
            Text("ARCHITECT", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black, fontSize = 14.sp)
            Text("Intent to governed workspace", color = OrchardColors.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
        }
        Divider(color = OrchardColors.divider)
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.Bottom,
        ) {
            Surface(color = OrchardColors.mossSoft, shape = RoundedCornerShape(6.dp)) {
                Text(message, modifier = Modifier.padding(14.dp), color = OrchardColors.ink, fontSize = 13.sp, lineHeight = 19.sp)
            }
        }
        Divider(color = OrchardColors.divider)
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = prompt,
                onValueChange = onPromptChange,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 88.dp, max = 180.dp)
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.isCtrlPressed && event.key == Key.Enter) {
                            onSubmit()
                            true
                        } else {
                            false
                        }
                    },
                placeholder = { Text("Describe what to create, including its name, description, and parent.", fontSize = 12.sp) },
                enabled = !isSubmitting,
                singleLine = false,
                minLines = 3,
                maxLines = 7,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                shape = RoundedCornerShape(6.dp),
                colors = TextFieldDefaults.outlinedTextFieldColors(
                    focusedBorderColor = OrchardColors.moss,
                    unfocusedBorderColor = OrchardColors.divider,
                    backgroundColor = OrchardColors.surface,
                ),
            )
            Spacer(Modifier.width(6.dp))
            IconButton(onClick = onSubmit, enabled = prompt.isNotBlank() && !isSubmitting) {
                if (isSubmitting) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = OrchardColors.moss)
                } else {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send to Architect", tint = OrchardColors.moss)
                }
            }
        }
    }
}