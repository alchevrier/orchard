package com.orchard.frontend.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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

private val ConductorCanvas = Color(0xFFF3F4F1)
private val ConductorSurface = Color(0xFFFCFCFA)
private val ConductorInk = Color(0xFF171A18)
private val ConductorMuted = Color(0xFF68706B)
private val ConductorLine = Color(0xFFD8DDD8)
private val ConductorGreen = Color(0xFF276749)
private val ConductorGreenSoft = Color(0xFFE2EEE7)
private val ConductorBlue = Color(0xFF315E84)
private val ConductorBlueSoft = Color(0xFFE4EDF4)
private val ConductorAmber = Color(0xFF8A601B)
private val ConductorAmberSoft = Color(0xFFF5EBD6)
private val ConductorRed = Color(0xFF9B4039)

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

    fun submit() {
        val content = prompt.trim()
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
        val current = projection
        if (current == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (error == null) CircularProgressIndicator(color = ConductorGreen)
                else Text(error.orEmpty(), color = ConductorRed)
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                ObjectiveRail(
                    objectives = current.objectives,
                    selectedObjectiveId = selectedObjectiveId,
                    onSelect = { selectedObjectiveId = it },
                    onControl = ::control,
                )
                Divider(Modifier.fillMaxHeight().width(1.dp), color = ConductorLine)
                Transcript(
                    modifier = Modifier.weight(1f),
                    messages = current.messages,
                    selectedObjectiveId = selectedObjectiveId,
                    prompt = prompt,
                    error = error,
                    isSubmitting = isSubmitting,
                    onPromptChange = { prompt = it },
                    onSubmit = ::submit,
                )
                Divider(Modifier.fillMaxHeight().width(1.dp), color = ConductorLine)
                AuthorityRail(
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
                )
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
}

@Composable
private fun ConductorHeader(
    conversations: List<ConversationListItemResponse>,
    selectedConversationId: Long?,
    isRefreshing: Boolean,
    onSelect: (Long) -> Unit,
    onCreate: () -> Unit,
    onRefresh: () -> Unit,
    onOpenAuthority: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = conversations.singleOrNull { it.conversation.conversationId == selectedConversationId }
    Row(
        Modifier.fillMaxWidth().height(64.dp).background(ConductorSurface).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text("ORCHARD CONDUCTOR", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Black, fontSize = 13.sp)
            Box {
                Text(
                    selected?.conversation?.title ?: "Select conversation",
                    modifier = Modifier.width(300.dp).clickable { expanded = true }.padding(top = 3.dp),
                    color = ConductorMuted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                    conversations.forEach { item ->
                        DropdownMenuItem(onClick = { expanded = false; onSelect(item.conversation.conversationId) }) {
                            Text("${item.conversation.title}  ·  ${item.objectiveCount} objectives")
                        }
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onOpenAuthority) { Text("Authority", color = ConductorInk) }
        IconButton(onClick = onCreate) { Icon(Icons.Default.Add, "New conversation", tint = ConductorGreen) }
        IconButton(onClick = onRefresh, enabled = !isRefreshing) {
            if (isRefreshing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = ConductorGreen)
            else Icon(Icons.Default.Refresh, "Refresh", tint = ConductorGreen)
        }
    }
}

@Composable
private fun ObjectiveRail(
    objectives: List<ConversationObjectiveResponse>,
    selectedObjectiveId: Long?,
    onSelect: (Long) -> Unit,
    onControl: (ConversationObjectiveResponse, String, Int?, List<Long>?) -> Unit,
) {
    Column(Modifier.width(270.dp).fillMaxHeight().background(ConductorSurface)) {
        Text("OBJECTIVES", Modifier.padding(18.dp), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        Divider(color = ConductorLine)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            objectives.sortedWith(compareByDescending<ConversationObjectiveResponse> { it.priority }.thenBy { it.objectiveId }).forEach { objective ->
                val selected = objective.objectiveId == selectedObjectiveId
                Column(
                    Modifier.fillMaxWidth().clickable { onSelect(objective.objectiveId) }
                        .background(if (selected) ConductorGreenSoft else Color.Transparent)
                        .padding(14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).background(objectiveStateColor(objective.state), RoundedCornerShape(4.dp)))
                        Text(objective.state.replace('_', ' '), Modifier.padding(start = 7.dp), color = objectiveStateColor(objective.state), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        Text("P${objective.priority}", color = ConductorMuted, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                    }
                    Text(objective.title, Modifier.padding(top = 8.dp), color = ConductorInk, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text(objective.outcome, Modifier.padding(top = 4.dp), color = ConductorMuted, fontSize = 11.sp, maxLines = if (selected) 4 else 2, overflow = TextOverflow.Ellipsis)
                    if (selected) {
                        ObjectiveControls(objective, objectives, onControl)
                    }
                }
                Divider(color = ConductorLine)
            }
            if (objectives.isEmpty()) {
                Text("Describe an outcome in the conversation to propose the first objective.", Modifier.padding(18.dp), color = ConductorMuted, fontSize = 12.sp)
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
    IconButton(onClick = onClick, modifier = Modifier.size(28.dp)) { Icon(icon, label, Modifier.size(15.dp), tint = ConductorGreen) }
}

@Composable
private fun Transcript(
    modifier: Modifier,
    messages: List<ConversationMessageResponse>,
    selectedObjectiveId: Long?,
    prompt: String,
    error: String?,
    isSubmitting: Boolean,
    onPromptChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Column(modifier.fillMaxHeight().background(ConductorCanvas)) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 30.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            messages.forEach { message -> MessageRow(message) }
            if (messages.isEmpty()) {
                Text("Start with an outcome, a status request, or a question about current authority.", color = ConductorMuted, fontSize = 14.sp)
            }
        }
        if (!error.isNullOrBlank()) {
            Text(error, Modifier.fillMaxWidth().background(ConductorAmberSoft).padding(horizontal = 18.dp, vertical = 7.dp), color = ConductorAmber, fontSize = 11.sp)
        }
        Divider(color = ConductorLine)
        Row(Modifier.fillMaxWidth().background(ConductorSurface).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = prompt,
                onValueChange = onPromptChange,
                modifier = Modifier.weight(1f).heightIn(min = 72.dp, max = 150.dp),
                placeholder = { Text(if (selectedObjectiveId == null) "Discuss or propose an objective" else "Continue objective $selectedObjectiveId", fontSize = 12.sp) },
                enabled = !isSubmitting,
                minLines = 2,
                maxLines = 6,
                shape = RoundedCornerShape(5.dp),
            )
            IconButton(onClick = onSubmit, enabled = prompt.isNotBlank() && !isSubmitting) {
                if (isSubmitting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = ConductorGreen)
                else Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = ConductorGreen)
            }
        }
    }
}

@Composable
private fun MessageRow(message: ConversationMessageResponse) {
    val user = message.role == "USER"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Column(Modifier.fillMaxWidth(0.82f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (user) "YOU" else "ORCHARD", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 9.sp, color = if (user) ConductorBlue else ConductorGreen)
                message.objectiveId?.let { Text("  OBJECTIVE $it", fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = ConductorMuted) }
            }
            Surface(
                color = if (user) ConductorBlueSoft else ConductorSurface,
                shape = RoundedCornerShape(5.dp),
                elevation = 0.dp,
                modifier = Modifier.padding(top = 5.dp),
            ) {
                Text(message.content, Modifier.padding(13.dp), color = ConductorInk, fontSize = 13.sp, lineHeight = 19.sp)
            }
        }
    }
}

@Composable
private fun AuthorityRail(
    commands: List<ConversationCommandViewResponse>,
    activities: List<com.orchard.frontend.network.ConversationActivityResponse>,
    selectedObjectiveId: Long?,
    onAdmit: (ConversationCommandViewResponse) -> Unit,
) {
    Column(Modifier.width(310.dp).fillMaxHeight().background(ConductorSurface)) {
        Text("AUTHORITY", Modifier.padding(18.dp), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        Divider(color = ConductorLine)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val visibleCommands = commands.filter { selectedObjectiveId == null || it.proposal.objectiveId == selectedObjectiveId }
            visibleCommands.filter { it.proposal.mutation && it.admission == null }.forEach { command ->
                Surface(color = ConductorAmberSoft, shape = RoundedCornerShape(5.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("ADMISSION REQUIRED", color = ConductorAmber, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 9.sp)
                        Text(command.proposal.capabilityId.replace('_', ' '), Modifier.padding(top = 6.dp), fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        Text(command.proposal.payloadJson, Modifier.padding(top = 5.dp), color = ConductorMuted, fontFamily = FontFamily.Monospace, fontSize = 9.sp, maxLines = 5, overflow = TextOverflow.Ellipsis)
                        TextButton(
                            onClick = { onAdmit(command) },
                            colors = ButtonDefaults.textButtonColors(contentColor = ConductorGreen),
                            modifier = Modifier.align(Alignment.End),
                        ) { Text("Admit exact command") }
                    }
                }
            }
            visibleCommands.filter { it.admission != null || !it.proposal.mutation }.takeLast(8).reversed().forEach { command ->
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
                Text("RECENT ACTIVITY", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 9.sp, color = ConductorMuted)
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
        text = { OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onCreate(title.trim()) }, enabled = title.isNotBlank()) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        shape = RoundedCornerShape(6.dp),
        backgroundColor = ConductorSurface,
    )
}

private fun objectiveStateColor(state: String) = when (state) {
    "ACTIVE", "READY", "COMPLETED" -> ConductorGreen
    "AWAITING_ADMISSION", "BLOCKED", "PAUSED" -> ConductorAmber
    "CANCELLED", "SUPERSEDED" -> ConductorRed
    else -> ConductorBlue
}

private val activeObjectiveStates = setOf("ACTIVE", "READY", "BLOCKED", "PAUSED", "AWAITING_ADMISSION")