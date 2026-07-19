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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.orchard.frontend.network.ControlConversationObjectiveRequest
import com.orchard.frontend.network.ConversationCommandViewResponse
import com.orchard.frontend.network.ConversationListItemResponse
import com.orchard.frontend.network.ConversationMessageResponse
import com.orchard.frontend.network.ConversationObjectiveResponse
import com.orchard.frontend.network.ConversationProjectionResponse
import com.orchard.frontend.network.DesktopNetworkClient
import com.orchard.frontend.network.SubmitConversationMessageRequest
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val ConductorCanvas = Color(0xFFF5F5F7)
private val ConductorSurface = Color(0xFFFFFFFF)
private val ConductorRaised = Color(0xFFFAFAFC)
private val ConductorInk = Color(0xFF1D1D1F)
private val ConductorMuted = Color(0xFF6E6E73)
private val ConductorLine = Color(0xFFE5E5EA)
private val ConductorGreen = Color(0xFF277A57)
private val ConductorGreenSoft = Color(0xFFEAF4EF)
private val ConductorBlue = Color(0xFF2877C7)
private val ConductorAmber = Color(0xFF936516)
private val ConductorAmberSoft = Color(0xFFFAF2E3)
private val ConductorRed = Color(0xFFB64A45)

@Composable
internal fun DurableConversationWorkspace(
    networkClient: DesktopNetworkClient,
    onOpenAuthority: () -> Unit,
) {
    var conversations by remember { mutableStateOf(emptyList<ConversationListItemResponse>()) }
    var projection by remember { mutableStateOf<ConversationProjectionResponse?>(null) }
    var selectedConversationId by remember { mutableStateOf<Long?>(null) }
    var selectedObjectiveId by remember { mutableStateOf<Long?>(null) }
    var prompt by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var isSubmitting by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }
    var showNewConversation by remember { mutableStateOf(false) }
    var showOnboardRepository by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    suspend fun loadConversations(createWhenEmpty: Boolean = false) {
        conversations = networkClient.getConversations()
        if (conversations.isEmpty() && createWhenEmpty) {
            val created = networkClient.createConversation("Orchard engineering")
            projection = created.projection
            selectedConversationId = created.projection?.conversation?.conversationId
            conversations = networkClient.getConversations()
        } else if (selectedConversationId == null || conversations.none { it.conversation.conversationId == selectedConversationId }) {
            selectedConversationId = conversations.lastOrNull()?.conversation?.conversationId
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

    LaunchedEffect(Unit) {
        runCatching { loadConversations(createWhenEmpty = true) }
            .onFailure { error = it.message ?: "Unable to load conversations." }
    }
    LaunchedEffect(selectedConversationId) {
        if (selectedConversationId == null) return@LaunchedEffect
        while (true) {
            runCatching { refresh(projection?.lastEventId ?: 0) }
                .onFailure { error = it.message ?: "Conversation refresh failed." }
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
            onRefresh = {
                if (!isRefreshing) scope.launch {
                    isRefreshing = true
                    runCatching { loadConversations(); refresh() }
                        .onFailure { error = it.message ?: "Unable to refresh the conversation." }
                    isRefreshing = false
                }
            },
            onOpenAuthority = onOpenAuthority,
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
                    )
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
                                    projection = response.projection ?: projection
                                    if (response.diagnostic.isNotBlank()) error = response.diagnostic
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
    onRefresh: () -> Unit,
    onOpenAuthority: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = conversations.singleOrNull { it.conversation.conversationId == selectedConversationId }
    Row(
        Modifier.fillMaxWidth().height(60.dp).background(ConductorSurface).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(320.dp)) {
            Text("Orchard", color = ConductorInk, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            Box {
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
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onOnboardRepository, modifier = Modifier.height(36.dp)) {
            Icon(Icons.Default.FolderOpen, null, Modifier.size(16.dp), tint = ConductorGreen)
            Spacer(Modifier.width(6.dp))
            Text("Onboard", color = ConductorGreen, fontSize = 12.sp)
        }
        Spacer(Modifier.width(6.dp))
        TextButton(onClick = onOpenAuthority, modifier = Modifier.height(36.dp)) {
            Text("Authority", color = ConductorMuted, fontSize = 12.sp)
        }
        Spacer(Modifier.width(6.dp))
        ToolbarIconButton(onClick = onCreate, label = "New conversation") {
            Icon(Icons.Default.Add, "New conversation", Modifier.size(17.dp), tint = ConductorInk)
        }
        Spacer(Modifier.width(6.dp))
        ToolbarIconButton(onClick = onRefresh, label = "Refresh", enabled = !isRefreshing) {
            if (isRefreshing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = ConductorGreen)
            else Icon(Icons.Default.Refresh, "Refresh", Modifier.size(17.dp), tint = ConductorInk)
        }
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
private fun ToolbarIconButton(
    onClick: () -> Unit,
    label: String,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.size(36.dp),
        shape = RoundedCornerShape(8.dp),
        color = ConductorRaised,
        border = BorderStroke(1.dp, ConductorLine),
    ) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(36.dp)) {
            Box(contentAlignment = Alignment.Center) { content() }
        }
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
private fun ObjectiveControls(
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
) {
    val transcriptScroll = rememberScrollState()
    LaunchedEffect(messages.size, isSubmitting) {
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
            latestOnboardedProjectId(commands)?.let { projectId ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { onSuggestedAction("Inspect repository for project $projectId") }) {
                        Icon(Icons.Default.Search, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Inspect repository")
                    }
                    TextButton(onClick = { onPromptChange("For project $projectId, I want to ") }) {
                        Icon(Icons.Default.Add, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Define objective")
                    }
                }
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
                placeholder = { Text(if (selectedObjectiveId == null) "Discuss or propose an objective" else "Continue objective $selectedObjectiveId", fontSize = 12.sp) },
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

internal fun latestOnboardedProjectId(commands: List<ConversationCommandViewResponse>): Int? = commands
    .asReversed()
    .firstNotNullOfOrNull { command ->
        command.executions.asReversed().firstOrNull {
            it.state == "CORRELATED" && it.downstreamType == "REPOSITORY_ONBOARDING"
        }?.downstreamId?.toIntOrNull()
    }

private fun objectiveStateColor(state: String) = when (state) {
    "ACTIVE", "READY", "COMPLETED" -> ConductorGreen
    "AWAITING_ADMISSION", "BLOCKED", "PAUSED" -> ConductorAmber
    "CANCELLED", "SUPERSEDED" -> ConductorRed
    else -> ConductorBlue
}

private val activeObjectiveStates = setOf("ACTIVE", "READY", "BLOCKED", "PAUSED", "AWAITING_ADMISSION")