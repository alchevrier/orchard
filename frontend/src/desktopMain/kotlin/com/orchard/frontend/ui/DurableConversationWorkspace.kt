package com.orchard.frontend.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Slider
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.TextField
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.orchard.frontend.network.ControlConversationObjectiveRequest
import com.orchard.frontend.network.CompanyWorkspaceResponse
import com.orchard.frontend.network.ConversationActivityResponse
import com.orchard.frontend.network.ConversationCommandViewResponse
import com.orchard.frontend.network.ConversationListItemResponse
import com.orchard.frontend.network.ConversationMessageResponse
import com.orchard.frontend.network.ConversationObjectiveResponse
import com.orchard.frontend.network.ConversationProjectionResponse
import com.orchard.frontend.network.DesktopNetworkClient
import com.orchard.frontend.network.EngineeringStandardSubmissionRequest
import com.orchard.frontend.network.EngineeringStandardsViewResponse
import com.orchard.frontend.network.GenesisProposalFailureException
import com.orchard.frontend.network.GenesisProposalResponse
import com.orchard.frontend.network.ProjectGenesisSubmissionRequest
import com.orchard.frontend.network.RepositoryObjectiveAssessmentResponse
import com.orchard.frontend.network.SubmitConversationMessageRequest
import com.orchard.frontend.network.WorkspaceSnapshotResponse
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val ConductorCanvas = OrchardDesktopColors.canvas
private val ConductorSurface = OrchardDesktopColors.surface
private val ConductorRaised = OrchardDesktopColors.raised
private val ConductorInk = OrchardDesktopColors.ink
private val ConductorMuted = OrchardDesktopColors.muted
private val ConductorLine = OrchardDesktopColors.line
private val ConductorGreen = OrchardDesktopColors.green
private val ConductorGreenSoft = OrchardDesktopColors.greenSoft
private val ConductorBlue = OrchardDesktopColors.blue
private val ConductorAmber = OrchardDesktopColors.amber
private val ConductorAmberSoft = OrchardDesktopColors.amberSoft
private val ConductorRed = OrchardDesktopColors.red

@Composable
internal fun DurableConversationWorkspace(
    networkClient: DesktopNetworkClient,
    initialConversationId: Long? = null,
    projectId: Int? = null,
    onOpenInbox: (Int) -> Unit = {},
    onOpenProject: (Int?) -> Unit = {},
    onProjectOnboarded: (Int) -> Unit = {},
) {
    var conversations by remember { mutableStateOf(emptyList<ConversationListItemResponse>()) }
    var projection by remember { mutableStateOf<ConversationProjectionResponse?>(null) }
    var selectedConversationId by remember(initialConversationId) { mutableStateOf(initialConversationId) }
    var selectedObjectiveId by remember { mutableStateOf<Long?>(null) }
    var prompt by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }
    var showNewConversation by remember { mutableStateOf(false) }
    var showOnboardRepository by remember { mutableStateOf(false) }
    var projectSetup by remember { mutableStateOf<ConductorProjectSetupState?>(null) }
    var genesisProposal by remember { mutableStateOf<GenesisProposalResponse?>(null) }
    var repositoryAssessment by remember { mutableStateOf<RepositoryObjectiveAssessmentResponse?>(null) }
    var genesisProposalFeedback by remember { mutableStateOf<String?>(null) }
    var isSetupSubmitting by remember { mutableStateOf(false) }
    var setupError by remember { mutableStateOf<String?>(null) }
    var deliveryGuidance by remember { mutableStateOf<DeliveryGuidance?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun loadConversations(createWhenEmpty: Boolean = false) {
        conversations = networkClient.getConversations()
        if (conversations.isEmpty() && createWhenEmpty) {
            val created = networkClient.createConversation("Orchard engineering")
            projection = created.projection
            selectedConversationId = created.projection?.conversation?.conversationId
            conversations = networkClient.getConversations()
        } else {
            selectedConversationId = focusedConversationId(
                initialConversationId,
                selectedConversationId,
                conversations.map { it.conversation.conversationId },
            )
        }
    }

    suspend fun refresh(afterEventId: Long = 0) {
        val conversationId = selectedConversationId ?: return
        projection = networkClient.getConversation(conversationId, afterEventId)
        val objectiveIds = projection?.objectives.orEmpty().map { it.objectiveId }
        if (selectedObjectiveId !in objectiveIds) {
            selectedObjectiveId = projection?.objectives?.firstOrNull { it.state in activeObjectiveStates }?.objectiveId
                ?: projection?.objectives?.firstOrNull()?.objectiveId
        }
    }

    suspend fun refreshProjectSetup(projectId: Int) {
        val workspace = networkClient.getWorkspace()
        val standards = networkClient.getEngineeringStandards(projectId)
        projectSetup = conductorProjectSetupState(projectId, workspace, standards)
        repositoryAssessment = networkClient.getLatestRepositoryAssessment(projectId)
    }

    LaunchedEffect(Unit) {
        runCatching { loadConversations(createWhenEmpty = true) }
            .onFailure { error = it.message ?: "Unable to load conversations." }
    }
    LaunchedEffect(initialConversationId) {
        if (initialConversationId != null && selectedConversationId != initialConversationId) {
            selectedConversationId = initialConversationId
            selectedObjectiveId = null
            projection = null
        }
    }
    LaunchedEffect(selectedConversationId) {
        if (selectedConversationId == null) return@LaunchedEffect
        while (true) {
            runCatching { refresh(projection?.lastEventId ?: 0) }
                .onFailure { error = it.message ?: "Conversation refresh failed." }
            delay(1_500)
        }
    }
    val onboardedProjectId = projection?.let { latestOnboardedProjectId(it.commands, it.activities) }
    LaunchedEffect(onboardedProjectId) {
        onboardedProjectId?.let(onProjectOnboarded)
    }
    val setupProjectId: Int? = null
    LaunchedEffect(setupProjectId) {
        if (setupProjectId == null) {
            projectSetup = null
            genesisProposal = null
            repositoryAssessment = null
            deliveryGuidance = null
        } else {
            runCatching { refreshProjectSetup(setupProjectId) }
                .onFailure { setupError = it.message ?: "Unable to load project setup." }
        }
    }
    LaunchedEffect(projectSetup?.genesis?.phase, projectSetup?.genesis?.revision?.hash) {
        genesisProposal = null
        genesisProposalFeedback = null
    }
    LaunchedEffect(setupProjectId, projectSetup?.genesis?.phase) {
        val projectId = setupProjectId ?: return@LaunchedEffect
        if (projectSetup?.genesis?.phase != "READY") return@LaunchedEffect
        while (true) {
            runCatching { networkClient.getCompanyState() }
                .onSuccess { deliveryGuidance = companyDeliveryGuidance(it, projectId) }
            delay(1_500)
        }
    }

    fun submitMessage(message: String) {
        val content = message.trim()
        val current = projection ?: return
        if (content.isEmpty() || isSubmitting) return
        prompt = ""
        isSubmitting = true
        error = null
        scope.launch {
            runCatching {
                val response = networkClient.submitConversationMessage(
                    current.conversation.conversationId,
                    SubmitConversationMessageRequest(
                        clientMessageId = "desktop:${UUID.randomUUID()}",
                        expectedSequence = current.messages.size + 1L,
                        content = content,
                        objectiveId = selectedObjectiveId,
                    ),
                )
                projection = response.projection ?: networkClient.getConversation(current.conversation.conversationId)
                if (response.diagnostic.isNotBlank()) error = response.diagnostic
                conversations = networkClient.getConversations()
            }.onFailure { error = it.message ?: "The conversation turn failed." }
            isSubmitting = false
        }
    }

    fun submit() = submitMessage(prompt)

    fun runSetupMutation(operation: suspend (ConductorProjectSetupState) -> Unit) {
        val current = projectSetup ?: return
        if (isSetupSubmitting) return
        isSetupSubmitting = true
        setupError = null
        scope.launch {
            runCatching {
                operation(current)
                refreshProjectSetup(current.projectId)
            }.onFailure { setupError = it.message ?: "Project setup could not advance." }
            isSetupSubmitting = false
        }
    }

    fun generateGenesisProposal(content: String) {
        val current = projectSetup ?: return
        if (isSetupSubmitting || content.isBlank()) return
        isSetupSubmitting = true
        setupError = null
        genesisProposalFeedback = null
        scope.launch {
            runCatching { networkClient.proposeProjectGenesis(current.projectId, content) }
                .onSuccess { genesisProposal = it }
                .onFailure { failure ->
                    val diagnostic = failure.message ?: "The Architect could not form a proposal."
                    genesisProposalFeedback = diagnostic
                    if ((failure as? GenesisProposalFailureException)?.canRefinePrompt != true) setupError = diagnostic
                }
            isSetupSubmitting = false
        }
    }

    fun createFirstOutcome(title: String) {
        val current = projectSetup ?: return
        val revision = current.genesis.revision ?: return
        if (isSetupSubmitting || title.isBlank()) return
        isSetupSubmitting = true
        setupError = null
        genesisProposalFeedback = null
        scope.launch {
            runCatching {
                networkClient.createProjectGenesisFirstOutcome(
                    current.projectId,
                    title,
                    revision.revision,
                    revision.hash,
                )
                refreshProjectSetup(current.projectId)
                genesisProposal = null
            }.onFailure { failure ->
                setupError = failure.message ?: "The first working outcome could not be recorded."
            }
            isSetupSubmitting = false
        }
    }

    fun startDelivery() {
        val current = projectSetup ?: return
        if (isSetupSubmitting) return
        isSetupSubmitting = true
        setupError = null
        deliveryGuidance = null
        scope.launch {
            runCatching { networkClient.startCompany(current.projectId) }
                .onSuccess { response ->
                    deliveryGuidance = companyDeliveryGuidance(response, current.projectId)
                    refreshProjectSetup(current.projectId)
                }
                .onFailure { failure ->
                    setupError = failure.message ?: "Governed delivery could not be started."
                }
            isSetupSubmitting = false
        }
    }

    fun control(objective: ConversationObjectiveResponse, action: String, priority: Int? = null, dependencies: List<Long>? = null) {
        scope.launch {
            runCatching {
                val response = networkClient.controlConversationObjective(
                    objective.objectiveId,
                    ControlConversationObjectiveRequest(
                        action,
                        objective.sourceMessageId,
                        objective.sourceMessageHash,
                        priority,
                        dependencies,
                    ),
                )
                projection = response.projection ?: projection
                if (response.diagnostic.isNotBlank()) error = response.diagnostic
            }.onFailure { error = it.message ?: "Objective control failed." }
        }
    }

    Column(Modifier.fillMaxSize().background(ConductorCanvas)) {
        ConductorHeader(
            conversations = conversations,
            selectedConversationId = selectedConversationId,
            isRefreshing = isRefreshing,
            onSelect = { selectedConversationId = it; projection = null; selectedObjectiveId = null },
            onCreate = { showNewConversation = true },
            onOnboardRepository = { showOnboardRepository = true },
            projectId = projectId,
            onOpenInbox = onOpenInbox,
            onOpenProject = onOpenProject,
            onRefresh = {
                if (!isRefreshing) scope.launch {
                    isRefreshing = true
                    runCatching {
                        loadConversations()
                        refresh()
                        setupProjectId?.let { refreshProjectSetup(it) }
                    }
                        .onFailure { error = it.message ?: "Unable to refresh the conversation." }
                    isRefreshing = false
                }
            },
        )
        Divider(color = ConductorLine)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val objectiveRailWidth = if (maxWidth < 1_250.dp) 224.dp else 252.dp
            val authorityRailWidth = if (maxWidth < 1_250.dp) 264.dp else 296.dp
            val current = projection
            if (current == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (error == null) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = ConductorGreen)
                    else Text(error.orEmpty(), color = ConductorRed, fontSize = 13.sp)
                }
            } else {
                val showAuthority = shouldShowAuthorityRail(current.commands, selectedObjectiveId)
                Row(Modifier.fillMaxSize()) {
                    ObjectiveRail(
                        width = objectiveRailWidth,
                        objectives = current.objectives,
                        selectedObjectiveId = selectedObjectiveId,
                        onSelect = { selectedObjectiveId = it },
                        onControl = ::control,
                    )
                    Divider(Modifier.fillMaxHeight().width(1.dp), color = ConductorLine)
                    Transcript(
                        modifier = Modifier.weight(1f),
                        messages = current.messages,
                        commands = current.commands,
                        selectedObjectiveId = selectedObjectiveId,
                        prompt = prompt,
                        error = error,
                        isSubmitting = isSubmitting,
                        onPromptChange = { prompt = it },
                        onSubmit = ::submit,
                        onOnboardRepository = { showOnboardRepository = true },
                        onSuggestedAction = ::submitMessage,
                        projectSetup = projectSetup,
                        genesisProposal = genesisProposal,
                        repositoryAssessment = repositoryAssessment,
                        genesisProposalFeedback = genesisProposalFeedback,
                        isSetupSubmitting = isSetupSubmitting,
                        setupError = setupError,
                        onAdoptStandards = { submission ->
                            runSetupMutation { setup ->
                                val result = networkClient.updateEngineeringStandard(setup.projectId, submission)
                                check(result.status == "UPDATED") {
                                    result.diagnostic.ifBlank { "Engineering standards were not recorded: ${result.status}." }
                                }
                            }
                        },
                        onAdvanceGenesis = { submission ->
                            runSetupMutation { setup -> networkClient.advanceProjectGenesis(setup.projectId, submission) }
                        },
                        onGenerateGenesisProposal = ::generateGenesisProposal,
                        onApplyGenesisProposal = {
                            genesisProposal?.let { proposal ->
                                runSetupMutation { setup ->
                                    check(proposal.projectId == setup.projectId && proposal.phase == setup.genesis.phase) {
                                        "The Genesis proposal is stale."
                                    }
                                    networkClient.advanceProjectGenesis(setup.projectId, proposal.submission)
                                }
                            }
                        },
                        onAdmitGenesis = {
                            runSetupMutation { setup -> networkClient.admitProjectGenesis(setup.projectId) }
                        },
                        onCreateFirstOutcome = ::createFirstOutcome,
                        deliveryGuidance = deliveryGuidance,
                        onStartDelivery = ::startDelivery,
                    )
                    if (showAuthority) {
                        Divider(Modifier.fillMaxHeight().width(1.dp), color = ConductorLine)
                        AuthorityRail(
                            width = authorityRailWidth,
                            commands = current.commands,
                            activities = current.activities,
                            selectedObjectiveId = selectedObjectiveId,
                            onAdmit = { command ->
                                scope.launch {
                                    runCatching {
                                        val response = networkClient.admitConversationCommand(command.proposal.commandId, command.proposal.hash)
                                        val updated = response.projection ?: projection
                                        projection = updated
                                        if (response.diagnostic.isNotBlank()) error = response.diagnostic
                                        updated?.let { latestOnboardedProjectId(it.commands, it.activities) }
                                            ?.let { refreshProjectSetup(it) }
                                    }.onFailure { error = it.message ?: "Command admission failed." }
                                }
                            },
                            onReject = { command ->
                                scope.launch {
                                    runCatching {
                                        val response = networkClient.rejectConversationCommand(command.proposal.commandId, command.proposal.hash)
                                        projection = response.projection ?: projection
                                        if (response.diagnostic.isNotBlank()) error = response.diagnostic
                                    }.onFailure { error = it.message ?: "Command rejection failed." }
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    if (showNewConversation) NewConversationDialog(
        onDismiss = { showNewConversation = false },
        onCreate = { title ->
            showNewConversation = false
            scope.launch {
                runCatching {
                    val created = networkClient.createConversation(title)
                    projection = created.projection
                    selectedConversationId = created.projection?.conversation?.conversationId
                    selectedObjectiveId = null
                    loadConversations()
                }.onFailure { error = it.message ?: "Conversation creation failed." }
            }
        },
    )
    if (showOnboardRepository) OnboardRepositoryDialog(
        onDismiss = { showOnboardRepository = false },
        onReview = { source, location, title ->
            showOnboardRepository = false
            val titleClause = title.takeIf(String::isNotBlank)?.let { " as project ${it.trim()}" }.orEmpty()
            val request = if (source == "GIT_URL") {
                "Onboard repository from Git URL: ${location.trim()}$titleClause"
            } else {
                "Onboard repository from local folder: ${location.trim()}$titleClause"
            }
            submitMessage(request)
        },
    )
}

@Composable
private fun ConductorHeader(
    conversations: List<ConversationListItemResponse>,
    selectedConversationId: Long?,
    isRefreshing: Boolean,
    onSelect: (Long) -> Unit,
    onCreate: () -> Unit,
    onOnboardRepository: () -> Unit,
    projectId: Int?,
    onOpenInbox: (Int) -> Unit,
    onOpenProject: (Int?) -> Unit,
    onRefresh: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = conversations.singleOrNull { it.conversation.conversationId == selectedConversationId }
    Row(
        Modifier.fillMaxWidth().height(60.dp).background(ConductorSurface).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text("Orchard", color = ConductorInk, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(320.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(28.dp)
                            .background(ConductorRaised, RoundedCornerShape(6.dp))
                            .clickable { expanded = true }
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ConversationTitleTooltip(
                            title = selected?.conversation?.title ?: "Select conversation",
                            modifier = Modifier.weight(1f),
                            color = ConductorMuted,
                        )
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            contentDescription = "Switch conversation",
                            modifier = Modifier.size(16.dp),
                            tint = ConductorMuted,
                        )
                    }
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false },
                        modifier = Modifier.width(420.dp),
                    ) {
                        conversations.forEach { item ->
                            val isSelected = item.conversation.conversationId == selectedConversationId
                            DropdownMenuItem(
                                onClick = { expanded = false; onSelect(item.conversation.conversationId) },
                                modifier = Modifier.background(if (isSelected) ConductorGreenSoft else Color.Transparent),
                            ) {
                                ConversationTitleTooltip(
                                    title = item.conversation.title,
                                    modifier = Modifier.weight(1f),
                                    color = if (isSelected) ConductorGreen else ConductorInk,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    "${item.objectiveCount} objectives",
                                    color = ConductorMuted,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                )
                                Spacer(Modifier.width(8.dp))
                                if (isSelected) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = "Current conversation",
                                        modifier = Modifier.size(16.dp),
                                        tint = ConductorGreen,
                                    )
                                } else {
                                    Spacer(Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.width(2.dp))
                PlainHeaderIconButton(onClick = onCreate) {
                    Icon(Icons.Default.Add, "New conversation", Modifier.size(17.dp), tint = ConductorInk)
                }
                Spacer(Modifier.width(2.dp))
                PlainHeaderIconButton(onClick = onRefresh, enabled = !isRefreshing) {
                    if (isRefreshing) CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp, color = ConductorGreen)
                    else Icon(Icons.Default.Refresh, "Refresh", Modifier.size(17.dp), tint = ConductorInk)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        if (projectId != null) {
            TextButton(onClick = { onOpenInbox(projectId) }, modifier = Modifier.height(36.dp)) {
                Text("Inbox", color = ConductorInk, fontSize = 12.sp)
            }
            Spacer(Modifier.width(4.dp))
        }
        TextButton(onClick = { onOpenProject(projectId) }, modifier = Modifier.height(36.dp)) {
            Icon(Icons.AutoMirrored.Filled.ViewList, null, Modifier.size(16.dp), tint = ConductorInk)
            Spacer(Modifier.width(6.dp))
            Text("Project", color = ConductorInk, fontSize = 12.sp)
        }
        Spacer(Modifier.width(4.dp))
        TextButton(onClick = onOnboardRepository, modifier = Modifier.height(36.dp)) {
            Icon(Icons.Default.FolderOpen, null, Modifier.size(16.dp), tint = ConductorGreen)
            Spacer(Modifier.width(6.dp))
            Text("Onboard", color = ConductorGreen, fontSize = 12.sp)
        }
    }
}

@Composable
private fun PlainHeaderIconButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(28.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationTitleTooltip(
    title: String,
    modifier: Modifier = Modifier,
    color: Color,
    fontWeight: FontWeight = FontWeight.Normal,
) {
    TooltipArea(
        tooltip = {
            Surface(
                color = ConductorInk,
                shape = RoundedCornerShape(6.dp),
            ) {
                Text(
                    title,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                    color = Color.White,
                    fontSize = 11.sp,
                    maxLines = 1,
                )
            }
        },
        modifier = modifier,
        delayMillis = 450,
        tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 16.dp)),
    ) {
        Text(
            title,
            color = color,
            fontSize = 11.sp,
            fontWeight = fontWeight,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ObjectiveRail(
    width: Dp,
    objectives: List<ConversationObjectiveResponse>,
    selectedObjectiveId: Long?,
    onSelect: (Long) -> Unit,
    onControl: (ConversationObjectiveResponse, String, Int?, List<Long>?) -> Unit,
) {
    Column(Modifier.width(width).fillMaxHeight().background(ConductorSurface)) {
        Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Objectives", color = ConductorInk, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Text(objectives.size.toString(), color = ConductorMuted, fontSize = 11.sp)
        }
        Divider(color = ConductorLine)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            objectives.sortedWith(compareByDescending<ConversationObjectiveResponse> { it.priority }.thenBy { it.objectiveId }).forEach { objective ->
                val selected = objective.objectiveId == selectedObjectiveId
                val background by animateColorAsState(
                    targetValue = if (selected) ConductorGreenSoft else Color.Transparent,
                    animationSpec = tween(180, easing = FastOutSlowInEasing),
                    label = "objective-background",
                )
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable { onSelect(objective.objectiveId) }
                        .animateContentSize(tween(220, easing = FastOutSlowInEasing)),
                    color = background,
                    shape = RoundedCornerShape(8.dp),
                    border = if (selected) BorderStroke(1.dp, ConductorLine) else null,
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 11.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(7.dp).background(objectiveStateColor(objective.state), RoundedCornerShape(4.dp)))
                            Text(objective.state.replace('_', ' ').lowercase(), Modifier.padding(start = 7.dp), color = objectiveStateColor(objective.state), fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.weight(1f))
                            Text(objective.priority.toString(), color = ConductorMuted, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                        }
                        Text(objective.title, Modifier.padding(top = 7.dp), color = ConductorInk, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 17.sp)
                        Text(objective.outcome, Modifier.padding(top = 4.dp), color = ConductorMuted, fontSize = 11.sp, lineHeight = 15.sp, maxLines = if (selected) 4 else 2, overflow = TextOverflow.Ellipsis)
                        AnimatedVisibility(
                            visible = selected,
                            enter = fadeIn(tween(180)) + expandVertically(tween(220)),
                            exit = fadeOut(tween(120)) + shrinkVertically(tween(180)),
                        ) {
                            ObjectiveControls(objective, objectives, onControl)
                        }
                    }
                }
            }
            if (objectives.isEmpty()) {
                Text("Describe an outcome to propose the first objective.", Modifier.padding(10.dp), color = ConductorMuted, fontSize = 12.sp, lineHeight = 17.sp)
            }
        }
    }
}

@Composable
internal fun ObjectiveControls(
    objective: ConversationObjectiveResponse,
    allObjectives: List<ConversationObjectiveResponse>,
    onControl: (ConversationObjectiveResponse, String, Int?, List<Long>?) -> Unit,
) {
    var priorityValue by remember(objective.objectiveId, objective.priority) { mutableStateOf(objective.priority.toFloat()) }
    Column(Modifier.padding(top = 10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            when (objective.state) {
                "AWAITING_ADMISSION" -> SmallIconAction(Icons.Default.Check, "Admit") { onControl(objective, "ADMIT", null, null) }
                "PAUSED" -> SmallIconAction(Icons.Default.PlayArrow, "Resume") { onControl(objective, "RESUME", null, null) }
                "READY", "ACTIVE", "BLOCKED" -> SmallIconAction(Icons.Default.Pause, "Pause") { onControl(objective, "PAUSE", null, null) }
            }
            if (objective.state !in setOf("COMPLETED", "CANCELLED", "SUPERSEDED")) {
                SmallIconAction(Icons.Default.Close, "Cancel") { onControl(objective, "CANCEL", null, null) }
            }
        }
        if (objective.state !in setOf("COMPLETED", "CANCELLED", "SUPERSEDED")) {
            Text("Priority ${priorityValue.roundToInt()}", color = ConductorMuted, fontSize = 9.sp)
            Slider(
                value = priorityValue,
                onValueChange = { priorityValue = it },
                onValueChangeFinished = {
                    onControl(objective, "SET_PRIORITY", priorityValue.roundToInt(), null)
                },
                valueRange = 0f..100f,
                modifier = Modifier.fillMaxWidth().height(24.dp),
            )
            val candidates = allObjectives.filter { it.objectiveId != objective.objectiveId && it.state != "CANCELLED" }
            if (candidates.isNotEmpty()) {
                Text("Dependencies", color = ConductorMuted, fontSize = 9.sp)
                candidates.forEach { candidate ->
                    val active = candidate.objectiveId in objective.dependencyObjectiveIds
                    Text(
                        (if (active) "✓ " else "+ ") + candidate.title,
                        modifier = Modifier.fillMaxWidth().clickable {
                            val next = if (active) objective.dependencyObjectiveIds - candidate.objectiveId
                            else objective.dependencyObjectiveIds + candidate.objectiveId
                            onControl(objective, "SET_DEPENDENCIES", null, next)
                        }.padding(vertical = 3.dp),
                        color = if (active) ConductorGreen else ConductorMuted,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun SmallIconAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(8.dp), color = ConductorRaised, border = BorderStroke(1.dp, ConductorLine)) {
        IconButton(onClick = onClick, modifier = Modifier.size(32.dp)) {
            Icon(icon, label, Modifier.size(15.dp), tint = ConductorGreen)
        }
    }
}

@Composable
private fun Transcript(
    modifier: Modifier,
    messages: List<ConversationMessageResponse>,
    commands: List<ConversationCommandViewResponse>,
    selectedObjectiveId: Long?,
    prompt: String,
    error: String?,
    isSubmitting: Boolean,
    onPromptChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onOnboardRepository: () -> Unit,
    onSuggestedAction: (String) -> Unit,
    projectSetup: ConductorProjectSetupState?,
    genesisProposal: GenesisProposalResponse?,
    repositoryAssessment: RepositoryObjectiveAssessmentResponse?,
    genesisProposalFeedback: String?,
    isSetupSubmitting: Boolean,
    setupError: String?,
    onAdoptStandards: (EngineeringStandardSubmissionRequest) -> Unit,
    onAdvanceGenesis: (ProjectGenesisSubmissionRequest) -> Unit,
    onGenerateGenesisProposal: (String) -> Unit,
    onApplyGenesisProposal: () -> Unit,
    onAdmitGenesis: () -> Unit,
    onCreateFirstOutcome: (String) -> Unit,
    deliveryGuidance: DeliveryGuidance?,
    onStartDelivery: () -> Unit,
) {
    val transcriptScroll = rememberScrollState()
    val setupStep = projectSetup?.let(::conductorSetupStep)
    LaunchedEffect(messages.size, isSubmitting, setupStep, genesisProposal?.phase) {
        delay(40)
        transcriptScroll.animateScrollTo(transcriptScroll.maxValue)
    }
    Column(modifier.fillMaxHeight().background(ConductorCanvas)) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(transcriptScroll).padding(horizontal = 34.dp, vertical = 26.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            messages.forEach { message -> MessageRow(message) }
            if (messages.isEmpty()) {
                Column(Modifier.widthIn(max = 420.dp).padding(top = 28.dp)) {
                    Text("What are we working toward?", color = ConductorInk, fontWeight = FontWeight.Medium, fontSize = 20.sp)
                    Text("Start with an outcome, a status request, or a question about current authority.", Modifier.padding(top = 8.dp), color = ConductorMuted, fontSize = 13.sp, lineHeight = 19.sp)
                    Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onOnboardRepository) {
                            Icon(Icons.Default.FolderOpen, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Onboard repository")
                        }
                        TextButton(onClick = { onSuggestedAction("Report current workspace status") }) {
                            Text("Request status")
                        }
                    }
                }
            }
            projectSetup?.let { setup ->
                ConductorProjectSetupCard(
                    state = setup,
                    proposal = genesisProposal,
                    repositoryAssessment = repositoryAssessment,
                    proposalFeedback = genesisProposalFeedback,
                    isSubmitting = isSubmitting || isSetupSubmitting,
                    error = setupError,
                    onAdoptStandards = onAdoptStandards,
                    onAdvance = onAdvanceGenesis,
                    onGenerateProposal = onGenerateGenesisProposal,
                    onApplyProposal = onApplyGenesisProposal,
                    onAdmit = onAdmitGenesis,
                    onCreateFirstOutcome = onCreateFirstOutcome,
                    onSuggestedAction = onSuggestedAction,
                    deliveryGuidance = deliveryGuidance,
                    onStartDelivery = onStartDelivery,
                )
            }
        }
        AnimatedVisibility(
            visible = !error.isNullOrBlank(),
            enter = fadeIn(tween(180)) + expandVertically(tween(180)),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(140)),
        ) {
            Text(error.orEmpty(), Modifier.fillMaxWidth().background(ConductorAmberSoft).padding(horizontal = 18.dp, vertical = 8.dp), color = ConductorAmber, fontSize = 11.sp)
        }
        Divider(color = ConductorLine)
        Row(
            Modifier.fillMaxWidth().background(ConductorSurface).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                value = prompt,
                onValueChange = onPromptChange,
                modifier = Modifier.weight(1f).heightIn(min = 56.dp, max = 132.dp),
                placeholder = {
                    Text(
                        when {
                            setupStep != null && setupStep != ConductorSetupStep.READY -> "Ask a question or add context for project setup"
                            selectedObjectiveId == null -> "Discuss or propose an objective"
                            else -> "Continue objective $selectedObjectiveId"
                        },
                        fontSize = 12.sp,
                    )
                },
                enabled = !isSubmitting,
                minLines = 2,
                maxLines = 5,
                shape = RoundedCornerShape(8.dp),
                colors = TextFieldDefaults.outlinedTextFieldColors(
                    backgroundColor = ConductorRaised,
                    focusedBorderColor = ConductorBlue,
                    unfocusedBorderColor = ConductorLine,
                    cursorColor = ConductorBlue,
                ),
            )
            val canSubmit = prompt.isNotBlank() && !isSubmitting
            Surface(
                modifier = Modifier.size(40.dp),
                shape = RoundedCornerShape(8.dp),
                color = if (canSubmit) ConductorBlue else ConductorRaised,
                border = if (canSubmit) null else BorderStroke(1.dp, ConductorLine),
            ) {
                IconButton(onClick = onSubmit, enabled = canSubmit, modifier = Modifier.size(40.dp)) {
                    if (isSubmitting) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp, color = ConductorBlue)
                    else Icon(Icons.AutoMirrored.Filled.Send, "Send", Modifier.size(17.dp), tint = if (canSubmit) Color.White else ConductorMuted)
                }
            }
        }
    }
}

@Composable
private fun MessageRow(message: ConversationMessageResponse) {
    val user = message.role == "USER"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Column(Modifier.fillMaxWidth(0.78f).widthIn(max = 720.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (user) "You" else "Orchard", fontWeight = FontWeight.SemiBold, fontSize = 10.sp, color = if (user) ConductorBlue else ConductorGreen)
                message.objectiveId?.let { Text("  Objective $it", fontSize = 9.sp, color = ConductorMuted) }
            }
            Surface(
                color = if (user) ConductorBlue else ConductorSurface,
                shape = RoundedCornerShape(8.dp),
                border = if (user) null else BorderStroke(1.dp, ConductorLine),
                elevation = 0.dp,
                modifier = Modifier.padding(top = 5.dp),
            ) {
                Text(message.content, Modifier.padding(horizontal = 14.dp, vertical = 11.dp), color = if (user) Color.White else ConductorInk, fontSize = 13.sp, lineHeight = 19.sp)
            }
        }
    }
}

@Composable
private fun AuthorityRail(
    width: Dp,
    commands: List<ConversationCommandViewResponse>,
    activities: List<com.orchard.frontend.network.ConversationActivityResponse>,
    selectedObjectiveId: Long?,
    onAdmit: (ConversationCommandViewResponse) -> Unit,
    onReject: (ConversationCommandViewResponse) -> Unit,
) {
    Column(Modifier.width(width).fillMaxHeight().background(ConductorSurface)) {
        Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Authority", color = ConductorInk, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        }
        Divider(color = ConductorLine)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val visibleCommands = commands.filter { selectedObjectiveId == null || it.proposal.objectiveId == selectedObjectiveId }
            visibleCommands.filter {
                it.proposal.mutation && it.admission == null && it.executions.lastOrNull()?.state != "REJECTED"
            }.forEach { command ->
                Surface(color = ConductorAmberSoft, shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, ConductorLine)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Admission required", color = ConductorAmber, fontWeight = FontWeight.SemiBold, fontSize = 10.sp)
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = { onReject(command) }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Close, "Dismiss proposal", Modifier.size(15.dp), tint = ConductorMuted)
                            }
                        }
                        Text(command.proposal.capabilityId.replace('_', ' '), Modifier.padding(top = 6.dp), fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        Text(command.proposal.payloadJson, Modifier.padding(top = 5.dp), color = ConductorMuted, fontFamily = FontFamily.Monospace, fontSize = 9.sp, maxLines = 5, overflow = TextOverflow.Ellipsis)
                        TextButton(
                            onClick = { onAdmit(command) },
                            colors = ButtonDefaults.textButtonColors(contentColor = ConductorGreen),
                            modifier = Modifier.align(Alignment.End),
                        ) { Text("Admit", fontSize = 12.sp) }
                    }
                }
            }
            visibleCommands.filter {
                it.admission != null || !it.proposal.mutation || it.executions.lastOrNull()?.state == "REJECTED"
            }.takeLast(8).reversed().forEach { command ->
                val execution = command.executions.lastOrNull()
                Column {
                    Text(command.proposal.capabilityId, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 9.sp, color = ConductorGreen)
                    Text(execution?.state ?: "PROPOSED", color = ConductorMuted, fontSize = 10.sp)
                    if (execution?.downstreamType != null) {
                        Text("${execution.downstreamType} · ${execution.downstreamId}", color = ConductorBlue, fontSize = 10.sp)
                    }
                }
                Divider(color = ConductorLine)
            }
            if (activities.isNotEmpty()) {
                Text("Recent activity", fontWeight = FontWeight.SemiBold, fontSize = 10.sp, color = ConductorMuted)
                activities.filter { selectedObjectiveId == null || it.objectiveId == selectedObjectiveId }.takeLast(8).reversed().forEach { activity ->
                    Column {
                        Text(activity.summary, color = ConductorInk, fontSize = 11.sp)
                        if (activity.authorityType != null) Text("${activity.authorityType} · ${activity.authorityId}", color = ConductorBlue, fontSize = 9.sp)
                    }
                }
            }
            if (visibleCommands.isEmpty() && activities.isEmpty()) {
                Text("Proposed commands and correlated authority will appear here.", color = ConductorMuted, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun NewConversationDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var title by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New conversation") },
        text = {
            TextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title") },
                singleLine = true,
                colors = TextFieldDefaults.textFieldColors(
                    backgroundColor = Color.Transparent,
                    focusedIndicatorColor = ConductorBlue,
                    unfocusedIndicatorColor = ConductorLine,
                ),
            )
        },
        confirmButton = { TextButton(onClick = { onCreate(title.trim()) }, enabled = title.isNotBlank()) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        shape = RoundedCornerShape(8.dp),
        backgroundColor = ConductorSurface,
    )
}

@Composable
private fun OnboardRepositoryDialog(
    onDismiss: () -> Unit,
    onReview: (source: String, location: String, title: String) -> Unit,
) {
    var source by remember { mutableStateOf("GIT_URL") }
    var location by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    val validLocation = if (source == "GIT_URL") {
        location.trim().startsWith("https://") || location.trim().startsWith("http://")
    } else {
        location.trim().startsWith("/") || Regex("^[A-Za-z]:[\\\\/].+").matches(location.trim())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Onboard repository") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, ConductorLine), color = ConductorRaised) {
                    Row {
                        TextButton(
                            onClick = { source = "GIT_URL" },
                            colors = ButtonDefaults.textButtonColors(contentColor = if (source == "GIT_URL") ConductorGreen else ConductorMuted),
                        ) { Text("Git URL") }
                        TextButton(
                            onClick = { source = "LOCAL_FOLDER" },
                            colors = ButtonDefaults.textButtonColors(contentColor = if (source == "LOCAL_FOLDER") ConductorGreen else ConductorMuted),
                        ) { Text("Local folder") }
                    }
                }
                TextField(
                    value = location,
                    onValueChange = { location = it },
                    label = { Text(if (source == "GIT_URL") "Repository URL" else "Absolute folder path") },
                    singleLine = true,
                    colors = TextFieldDefaults.textFieldColors(backgroundColor = Color.Transparent),
                )
                TextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Project title (optional)") },
                    singleLine = true,
                    colors = TextFieldDefaults.textFieldColors(backgroundColor = Color.Transparent),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onReview(source, location.trim(), title.trim()) }, enabled = validLocation) {
                Text("Review proposal")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        shape = RoundedCornerShape(8.dp),
        backgroundColor = ConductorSurface,
    )
}

internal fun onboardedProjectId(command: ConversationCommandViewResponse): Int? =
    if (command.proposal.capabilityId != "ONBOARD_REPOSITORY") null
    else command.executions.asReversed().firstOrNull {
            it.state == "CORRELATED" && it.downstreamType == "REPOSITORY_ONBOARDING"
        }?.downstreamId?.toIntOrNull()

internal fun latestOnboardedProjectId(commands: List<ConversationCommandViewResponse>): Int? =
    commands.asReversed().firstNotNullOfOrNull(::onboardedProjectId)

internal fun latestOnboardedProjectId(
    commands: List<ConversationCommandViewResponse>,
    activities: List<ConversationActivityResponse>,
): Int? = latestOnboardedProjectId(commands)
    ?: activities.asReversed().firstOrNull { it.authorityType == "REPOSITORY_ONBOARDING" }
        ?.authorityId?.toIntOrNull()

internal fun focusedConversationId(
    requestedConversationId: Long?,
    currentConversationId: Long?,
    availableConversationIds: List<Long>,
): Long? = requestedConversationId
    ?: currentConversationId?.takeIf { it in availableConversationIds }
    ?: availableConversationIds.lastOrNull()

internal fun shouldShowAuthorityRail(
    commands: List<ConversationCommandViewResponse>,
    selectedObjectiveId: Long?,
): Boolean = commands.any { command ->
    (selectedObjectiveId == null || command.proposal.objectiveId == selectedObjectiveId) &&
        command.proposal.mutation && command.admission == null &&
        command.executions.lastOrNull()?.state != "REJECTED"
}

internal fun companyDeliveryGuidance(response: CompanyWorkspaceResponse, projectId: Int): DeliveryGuidance? {
    val company = response.companyProjects.singleOrNull { it.projectId == projectId } ?: return null
    company.requiredDecision?.takeIf(String::isNotBlank)?.let { decision ->
        return DeliveryGuidance("Orchard is waiting for this decision: $decision", actionRequired = true)
    }
    val run = response.workspace.workflowRuns.lastOrNull { it.context.projectId == projectId }
    val hasExecutionPlan = run?.let { candidate -> response.executionPlans.any { it.runId == candidate.runId } } == true
    val message = when (run?.state) {
        "CONTEXT_READY" -> if (hasExecutionPlan) {
            "The repository plan is ready and the local coding worker is implementing the first outcome. No action is needed. Keep Orchard running; you can leave this screen and return later. This status updates automatically."
        } else {
            "Orchard is analyzing repository evidence before coding the first outcome. No action is needed. Keep Orchard running; you can leave this screen and return later. This status updates automatically."
        }
        "EVIDENCE_PENDING" ->
            "A candidate has been produced and Orchard is verifying and auditing it. No action is needed unless a decision appears here."
        "EVIDENCE_BLOCKED" ->
            "The current candidate did not satisfy its evidence gates. Orchard is preparing a governed repair; no action is needed unless a decision appears here."
        "DONE" -> "The first delivery run passed its evidence gates. Orchard will show any promotion decision here."
        "CANCELLED" -> return DeliveryGuidance("The delivery run was cancelled. Start a new outcome when you are ready.", actionRequired = true)
        else -> when (company.phase) {
            "DELIVERY_PLANNING", "STAFFING" ->
                "Orchard is forming the first governed delivery run. No action is needed; this status updates automatically."
            else -> "Orchard is advancing delivery automatically. No action is needed unless a decision appears here."
        }
    }
    return DeliveryGuidance(message)
}

internal fun conductorProjectSetupState(
    projectId: Int,
    workspace: WorkspaceSnapshotResponse,
    standards: EngineeringStandardsViewResponse,
): ConductorProjectSetupState {
    val resources = workspace.resources.values
    val project = requireNotNull(resources.singleOrNull {
        it.type == "PROJECT" && workspaceActionValue(it.action, "id") == projectId
    }) { "Project $projectId is not present in workspace authority." }
    val genesis = requireNotNull(workspace.projectGenesis.singleOrNull { it.projectId == projectId }) {
        "Project $projectId has no Genesis projection."
    }
    val epics = resources.filter {
        it.type == "EPIC" && workspaceActionValue(it.action, "parent") == projectId
    }.map { workspaceActionValue(it.action, "id") to it.path }
    return ConductorProjectSetupState(projectId, project.path, genesis, standards, epics)
}

private fun workspaceActionValue(action: String, key: String): Int {
    val marker = "$key="
    val start = action.indexOf(marker)
    if (start == -1) return 0
    val valueStart = start + marker.length
    val valueEnd = action.indexOf(';', valueStart).let { if (it == -1) action.length else it }
    return action.substring(valueStart, valueEnd).toIntOrNull() ?: 0
}

private fun objectiveStateColor(state: String) = when (state) {
    "ACTIVE", "READY", "COMPLETED" -> ConductorGreen
    "AWAITING_ADMISSION", "BLOCKED", "PAUSED" -> ConductorAmber
    "CANCELLED", "SUPERSEDED" -> ConductorRed
    else -> ConductorBlue
}

private val activeObjectiveStates = setOf("ACTIVE", "READY", "BLOCKED", "PAUSED", "AWAITING_ADMISSION")