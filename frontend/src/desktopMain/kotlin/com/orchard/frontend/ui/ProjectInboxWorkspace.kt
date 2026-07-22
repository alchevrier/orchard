package com.orchard.frontend.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orchard.frontend.network.CreateProjectReportRequest
import com.orchard.frontend.network.ControlConversationObjectiveRequest
import com.orchard.frontend.network.ConversationCommandViewResponse
import com.orchard.frontend.network.ConversationObjectiveResponse
import com.orchard.frontend.network.ConversationProjectionResponse
import com.orchard.frontend.network.DesktopNetworkClient
import com.orchard.frontend.network.ProjectGenesisViewResponse
import com.orchard.frontend.network.ProjectReportFilterDataResponse
import com.orchard.frontend.network.ProjectReportInboxResponse
import com.orchard.frontend.network.ReportItemInputRequest
import com.orchard.frontend.network.ReportItemResponse
import com.orchard.frontend.network.ReportRevisionProjectionResponse
import com.orchard.frontend.network.ReportScopeRequest
import com.orchard.frontend.network.ReportThreadLinkResponse
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal enum class ProjectInboxFilter(val wireValue: String, val label: String) {
    UNREAD("unread", "Unread"),
    ACTION_REQUIRED("action-required", "Action required"),
    SUBSCRIBED("subscribed", "Subscribed"),
    BLOCKED("blocked", "Blocked"),
    COMPLETED("completed", "Completed"),
}

internal enum class FirstOutcomeActionType { CREATE, ADMIT, THREAD_PROMPT }

internal data class FirstOutcomeAction(
    val type: FirstOutcomeActionType,
    val label: String,
    val prompt: String = "",
)

internal fun toggleInboxFilter(
    current: Set<ProjectInboxFilter>,
    filter: ProjectInboxFilter,
): Set<ProjectInboxFilter> = if (filter in current) current - filter else current + filter

internal fun firstOutcomeAction(genesis: ProjectGenesisViewResponse?): FirstOutcomeAction? {
    if (genesis?.phase == "READY") return null
    if (genesis?.revision?.firstEpicId != null && genesis.phase in setOf("ADMISSION", "BLUEPRINT")) {
        return FirstOutcomeAction(FirstOutcomeActionType.ADMIT, "Admit intent and first outcome")
    }
    if (genesis?.revision?.firstEpicId != null) return null
    return if ((genesis?.phase ?: "CLASSIFICATION") in setOf("CLASSIFICATION", "EXPERIENCE", "ARCHITECTURE")) {
        FirstOutcomeAction(FirstOutcomeActionType.CREATE, "Confirm intent and record outcome")
    } else {
        FirstOutcomeAction(
            FirstOutcomeActionType.THREAD_PROMPT,
            "Confirm intent and define first outcome",
            FIRST_OUTCOME_THREAD_PROMPT,
        )
    }
}

internal const val FIRST_OUTCOME_THREAD_PROMPT =
    "Review the revision-pinned repository baseline for this project. Confirm or correct the inferred current intent, then help me define the first desired outcome without inventing architecture or implementation authority."

internal fun openResolvedThread(
    link: ReportThreadLinkResponse,
    onOpenThread: (Long, Int) -> Unit,
) = onOpenThread(link.conversationId, link.projectId)

internal fun inboxReportForConversation(
    reports: List<ReportRevisionProjectionResponse>,
    conversationId: Long,
): ReportRevisionProjectionResponse? = reports.firstOrNull { it.thread?.conversationId == conversationId }

@Composable
internal fun ProjectInboxWorkspace(
    networkClient: DesktopNetworkClient,
    projectId: Int,
    initialConversationId: Long? = null,
    onOpenProject: (Int) -> Unit,
) {
    var inbox by remember(projectId) { mutableStateOf(ProjectReportInboxResponse(projectId)) }
    var genesis by remember(projectId) { mutableStateOf<ProjectGenesisViewResponse?>(null) }
    var projectTitle by remember(projectId) { mutableStateOf("Project $projectId") }
    var selectedFilters by remember(projectId) { mutableStateOf(emptySet<ProjectInboxFilter>()) }
    var selectedReportId by remember(projectId) { mutableStateOf<Long?>(null) }
    var selectedRevision by remember(projectId) { mutableStateOf<Int?>(null) }
    var error by remember(projectId) { mutableStateOf<String?>(null) }
    var isLoading by remember(projectId) { mutableStateOf(true) }
    var isMutating by remember(projectId) { mutableStateOf(false) }
    var showNewConversation by remember(projectId) { mutableStateOf(false) }
    var confirmedIntent by remember(projectId) { mutableStateOf("") }
    var outcomeTitle by remember(projectId) { mutableStateOf("") }
    var threadLink by remember(projectId) { mutableStateOf<ReportThreadLinkResponse?>(null) }
    var conversation by remember(projectId) { mutableStateOf<ConversationProjectionResponse?>(null) }
    var conversationPrompt by remember(projectId) { mutableStateOf("") }
    var conversationError by remember(projectId) { mutableStateOf<String?>(null) }
    var isConversationSubmitting by remember(projectId) { mutableStateOf(false) }
    var requestedConversationId by remember(projectId, initialConversationId) { mutableStateOf(initialConversationId) }
    val scope = rememberCoroutineScope()

    suspend fun refresh() {
        val nextInbox = networkClient.getProjectReports(projectId, selectedFilters.mapTo(mutableSetOf()) { it.wireValue })
        val workspace = networkClient.getWorkspace()
        inbox = nextInbox
        genesis = workspace.projectGenesis.singleOrNull { it.projectId == projectId }
        if (confirmedIntent.isBlank()) confirmedIntent = genesis?.revision?.productIntent.orEmpty()
        projectTitle = workspace.resources.values.singleOrNull {
            it.type == "PROJECT" && inboxActionValue(it.action, "id") == projectId
        }?.path ?: projectTitle
        requestedConversationId?.let { conversationId ->
            inboxReportForConversation(nextInbox.reports, conversationId)?.let { report ->
                selectedReportId = report.report.reportId
                selectedRevision = report.revision.revision
                requestedConversationId = null
            }
        }
        val selectedStillExists = nextInbox.reports.any {
            it.report.reportId == selectedReportId && it.revision.revision == selectedRevision
        }
        if (!selectedStillExists) {
            selectedReportId = nextInbox.reports.firstOrNull()?.report?.reportId
            selectedRevision = nextInbox.reports.firstOrNull()?.revision?.revision
        }
    }

    fun mutate(operation: suspend () -> Unit) {
        if (isMutating) return
        isMutating = true
        error = null
        scope.launch {
            runCatching {
                operation()
                refresh()
            }.onFailure { error = it.message ?: "The report action failed." }
            isMutating = false
        }
    }

    suspend fun resolveConversation(report: ReportRevisionProjectionResponse): ReportThreadLinkResponse {
        val current = threadLink?.takeIf { it.conversationId == report.thread?.conversationId }
        val link = report.thread ?: current ?: networkClient.resolveProjectThread(
            projectId,
            "REPORT",
            report.report.reportId,
        )
        threadLink = link
        if (conversation?.conversation?.conversationId != link.conversationId) {
            conversation = networkClient.getConversation(link.conversationId)
        }
        return link
    }

    fun continueConversation(
        report: ReportRevisionProjectionResponse,
        prompt: String? = null,
        advisory: Boolean = false,
    ) {
        if (isMutating) return
        isMutating = true
        error = null
        scope.launch {
            runCatching {
                if (report.unread) {
                    networkClient.markProjectReportRead(projectId, report.report.reportId, report.revision.revision)
                }
                val link = resolveConversation(report)
                if (!prompt.isNullOrBlank()) {
                    val response = networkClient.submitConversationMessageAtCurrentSequence(
                        conversationId = link.conversationId,
                        clientMessageId = "desktop:${UUID.randomUUID()}",
                        content = prompt,
                        intent = if (advisory) "ADVISORY" else "STANDARD",
                    )
                    check(response.status in setOf("RECORDED", "ALREADY_RECORDED", "ADMISSION_REQUIRED", "REJECTED")) {
                        response.diagnostic.ifBlank { "The canonical thread did not record the requested message." }
                    }
                    conversation = response.projection ?: networkClient.getConversation(link.conversationId)
                    conversationError = response.diagnostic.takeIf(String::isNotBlank)
                }
            }.onFailure { error = it.message ?: "The canonical report thread could not be opened." }
            isMutating = false
        }
    }

    fun submitConversation() {
        val report = inbox.reports.singleOrNull {
            it.report.reportId == selectedReportId && it.revision.revision == selectedRevision
        } ?: return
        val content = conversationPrompt.trim()
        if (content.isEmpty() || isConversationSubmitting) return
        conversationPrompt = ""
        isConversationSubmitting = true
        conversationError = null
        scope.launch {
            runCatching {
                val link = resolveConversation(report)
                val response = networkClient.submitConversationMessageAtCurrentSequence(
                    link.conversationId,
                    "desktop:${UUID.randomUUID()}",
                    content,
                )
                conversation = response.projection ?: networkClient.getConversation(link.conversationId)
                conversationError = response.diagnostic.takeIf(String::isNotBlank)
            }.onFailure { conversationError = it.message ?: "The conversation reply failed." }
            isConversationSubmitting = false
        }
    }

    fun mutateConversation(operation: suspend (ConversationProjectionResponse) -> Unit) {
        val current = conversation ?: return
        if (isConversationSubmitting) return
        isConversationSubmitting = true
        conversationError = null
        scope.launch {
            runCatching { operation(current) }
                .onFailure { conversationError = it.message ?: "The conversation action failed." }
            isConversationSubmitting = false
        }
    }

    LaunchedEffect(projectId, selectedFilters) {
        while (true) {
            runCatching { refresh() }
                .onFailure { error = it.message ?: "Unable to load the project inbox." }
            isLoading = false
            delay(1_500)
        }
    }

    val selected = inbox.reports.singleOrNull {
        it.report.reportId == selectedReportId && it.revision.revision == selectedRevision
    } ?: inbox.reports.firstOrNull()
    val outcomeAction = firstOutcomeAction(genesis)

    LaunchedEffect(selected?.report?.reportId) {
        val report = selected ?: run {
            threadLink = null
            conversation = null
            return@LaunchedEffect
        }
        threadLink = null
        conversation = null
        conversationError = null
        while (true) {
            runCatching {
                val link = resolveConversation(report)
                conversation = networkClient.getConversation(link.conversationId)
            }.onFailure { conversationError = it.message ?: "The embedded conversation could not be loaded." }
            delay(1_500)
        }
    }

    Column(Modifier.fillMaxSize().background(InboxSurface)) {
        ProjectWorkspaceNavigation(projectTitle, "INBOX", { }, { onOpenProject(projectId) })
        Divider(color = InboxLine)
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.width(430.dp).fillMaxHeight().background(InboxListSurface)) {
                InboxToolbar(
                    filters = selectedFilters,
                    counts = inbox.filters,
                    isLoading = isLoading,
                    onToggle = { selectedFilters = toggleInboxFilter(selectedFilters, it) },
                    onCreate = { showNewConversation = true },
                    onRefresh = { scope.launch { runCatching { refresh() }.onFailure { error = it.message } } },
                )
                Divider(color = InboxLine)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    inbox.reports.forEach { report ->
                        InboxReportRow(
                            report = report,
                            selected = report.report.reportId == selectedReportId && report.revision.revision == selectedRevision,
                            onSelect = {
                                selectedReportId = report.report.reportId
                                selectedRevision = report.revision.revision
                                if (report.unread) mutate {
                                    networkClient.markProjectReportRead(projectId, report.report.reportId, report.revision.revision)
                                }
                            },
                        )
                    }
                    if (!isLoading && inbox.reports.isEmpty()) {
                        Text(
                            "No report revisions match these filters.",
                            Modifier.padding(24.dp),
                            color = InboxMuted,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
            Divider(Modifier.fillMaxHeight().width(1.dp), color = InboxLine)
            Column(Modifier.weight(1f).fillMaxHeight()) {
                if (!error.isNullOrBlank()) {
                    Text(error.orEmpty(), Modifier.fillMaxWidth().background(InboxAmberSoft).padding(10.dp), color = InboxAmber, fontSize = 11.sp)
                }
                if (selected == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Select a report revision.", color = InboxMuted)
                    }
                } else {
                    ReportDetail(
                        report = selected,
                        isMutating = isMutating,
                        outcomeAction = outcomeAction,
                        confirmedIntent = confirmedIntent,
                        onConfirmedIntentChange = { confirmedIntent = it },
                        outcomeTitle = outcomeTitle,
                        onOutcomeTitleChange = { outcomeTitle = it },
                        onPlanRemediation = { item ->
                            continueConversation(selected, requireNotNull(item.remediation).prompt, advisory = true)
                        },
                        onPromptForOutcome = { continueConversation(selected, requireNotNull(outcomeAction).prompt) },
                        onCreateOutcome = {
                            if (confirmedIntent.isNotBlank() && outcomeTitle.isNotBlank()) mutate {
                                networkClient.createProjectGenesisFirstOutcome(
                                    projectId,
                                    outcomeTitle.trim(),
                                    genesis?.revision?.revision ?: 0,
                                    genesis?.revision?.hash,
                                    confirmedIntent.trim(),
                                )
                                outcomeTitle = ""
                            }
                        },
                        onAdmitDirection = { mutate { networkClient.admitProjectGenesis(projectId) } },
                        onSubscribe = { mode -> mutate {
                            networkClient.subscribeToProjectReport(projectId, selected.report.reportId, mode)
                        } },
                        onPause = { mutate {
                            networkClient.pauseProjectReportSubscription(projectId, selected.report.reportId)
                        } },
                        onResume = { mutate {
                            networkClient.resumeProjectReportSubscription(projectId, selected.report.reportId)
                        } },
                        conversation = conversation,
                        conversationPrompt = conversationPrompt,
                        conversationError = conversationError,
                        isConversationSubmitting = isConversationSubmitting,
                        onConversationPromptChange = { conversationPrompt = it },
                        onSubmitConversation = ::submitConversation,
                        onAdmitCommand = { command -> mutateConversation {
                            val response = networkClient.admitConversationCommand(command.proposal.commandId, command.proposal.hash)
                            conversation = response.projection ?: conversation
                            conversationError = response.diagnostic.takeIf(String::isNotBlank)
                        } },
                        onRejectCommand = { command -> mutateConversation {
                            val response = networkClient.rejectConversationCommand(command.proposal.commandId, command.proposal.hash)
                            conversation = response.projection ?: conversation
                            conversationError = response.diagnostic.takeIf(String::isNotBlank)
                        } },
                        onControlObjective = { objective, action, priority, dependencies -> mutateConversation {
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
                            conversation = response.projection ?: conversation
                            conversationError = response.diagnostic.takeIf(String::isNotBlank)
                        } },
                    )
                }
            }
        }
    }

    if (showNewConversation) {
        NewConversationDialog(
            projectId = projectId,
            isSubmitting = isMutating,
            onDismiss = { if (!isMutating) showNewConversation = false },
            onCreate = { clientRequestId, title, summary, scopeType, targetId, subscriptionMode ->
                selectedFilters = emptySet()
                mutate {
                    val publication = networkClient.createProjectReport(
                        projectId,
                        CreateProjectReportRequest(
                            clientRequestId = clientRequestId,
                            scope = ReportScopeRequest(scopeType, targetId),
                            title = title,
                            items = listOf(ReportItemInputRequest(
                                itemKey = "conversation-${clientRequestId.take(8)}",
                                kind = "CONVERSATION",
                                state = "OPEN",
                                title = title,
                                summary = summary,
                            )),
                        ),
                    )
                    if (subscriptionMode != null) {
                        networkClient.subscribeToProjectReport(projectId, publication.report.reportId, subscriptionMode)
                    }
                    val link = networkClient.resolveProjectThread(
                        projectId,
                        "REPORT",
                        publication.report.reportId,
                    )
                    val response = networkClient.submitConversationMessageAtCurrentSequence(
                        link.conversationId,
                        "desktop:$clientRequestId",
                        summary,
                    )
                    check(response.status in setOf("RECORDED", "ALREADY_RECORDED", "ADMISSION_REQUIRED", "REJECTED")) {
                        response.diagnostic.ifBlank { "The new conversation could not be started." }
                    }
                    selectedReportId = publication.report.reportId
                    selectedRevision = publication.revision.revision
                    threadLink = link
                    conversation = response.projection ?: networkClient.getConversation(link.conversationId)
                    conversationError = response.diagnostic.takeIf(String::isNotBlank)
                    refresh()
                    showNewConversation = false
                }
            },
        )
    }
}

@Composable
internal fun ProjectWorkspaceNavigation(
    projectTitle: String,
    selected: String,
    onInbox: () -> Unit,
    onProject: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(58.dp).background(InboxSurface).padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(projectTitle, color = InboxInk, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text("Project workspace", color = InboxMuted, fontSize = 10.sp)
        }
        Spacer(Modifier.weight(1f))
        listOf("INBOX" to onInbox, "PROJECT" to onProject).forEach { (name, action) ->
            val active = selected == name
            TextButton(
                onClick = action,
                colors = ButtonDefaults.textButtonColors(backgroundColor = if (active) InboxGreenSoft else Color.Transparent),
                shape = RoundedCornerShape(6.dp),
            ) {
                Text(name.lowercase().replaceFirstChar(Char::uppercase), color = if (active) InboxGreen else InboxMuted, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
            }
            Spacer(Modifier.width(4.dp))
        }
    }
}

@Composable
private fun InboxToolbar(
    filters: Set<ProjectInboxFilter>,
    counts: ProjectReportFilterDataResponse,
    isLoading: Boolean,
    onToggle: (ProjectInboxFilter) -> Unit,
    onCreate: () -> Unit,
    onRefresh: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Inbox", color = InboxInk, fontWeight = FontWeight.Bold, fontSize = 19.sp)
            Text(" ${counts.total}", color = InboxMuted, fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onCreate, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Default.Add, "New conversation", tint = InboxGreen, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onRefresh, modifier = Modifier.size(30.dp)) {
                if (isLoading) CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp, color = InboxGreen)
                else Icon(Icons.Default.Refresh, "Refresh inbox", tint = InboxMuted, modifier = Modifier.size(17.dp))
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 9.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            ProjectInboxFilter.entries.forEach { filter ->
                val count = when (filter) {
                    ProjectInboxFilter.UNREAD -> counts.unread
                    ProjectInboxFilter.ACTION_REQUIRED -> counts.actionRequired
                    ProjectInboxFilter.SUBSCRIBED -> counts.subscribed
                    ProjectInboxFilter.BLOCKED -> counts.blocked
                    ProjectInboxFilter.COMPLETED -> counts.completed
                }
                Surface(
                    color = if (filter in filters) InboxGreenSoft else InboxSurface,
                    shape = RoundedCornerShape(5.dp),
                    border = BorderStroke(1.dp, if (filter in filters) InboxGreen else InboxLine),
                    modifier = Modifier.clickable { onToggle(filter) },
                ) {
                    Text("${filter.label} $count", Modifier.padding(horizontal = 7.dp, vertical = 5.dp), color = if (filter in filters) InboxGreen else InboxMuted, fontSize = 9.sp)
                }
            }
        }
    }
}

@Composable
private fun InboxReportRow(
    report: ReportRevisionProjectionResponse,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(if (selected) InboxSelected else Color.Transparent).clickable(onClick = onSelect)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier.padding(top = 5.dp).size(7.dp).background(if (report.unread) InboxBlue else Color.Transparent, RoundedCornerShape(4.dp)),
        )
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(report.report.title, Modifier.weight(1f), color = InboxInk, fontWeight = if (report.unread) FontWeight.Bold else FontWeight.Medium, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(report.revision.createdAt.inboxTimestamp(), color = InboxMuted, fontSize = 9.sp)
            }
            Text(report.items.firstOrNull()?.summary.orEmpty(), Modifier.padding(top = 4.dp), color = InboxMuted, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("R${report.revision.revision}", color = InboxMuted, fontFamily = FontFamily.Monospace, fontSize = 8.sp)
                if (report.actionRequired) Text("ACTION REQUIRED", color = InboxAmber, fontWeight = FontWeight.Bold, fontSize = 8.sp)
                if (report.blocked) Text("BLOCKED", color = InboxRed, fontWeight = FontWeight.Bold, fontSize = 8.sp)
                if (report.subscribed) Text(report.subscription?.mode.orEmpty(), color = InboxGreen, fontWeight = FontWeight.Bold, fontSize = 8.sp)
            }
        }
    }
    Divider(color = InboxLine)
}

@Composable
private fun ReportDetail(
    report: ReportRevisionProjectionResponse,
    isMutating: Boolean,
    outcomeAction: FirstOutcomeAction?,
    confirmedIntent: String,
    onConfirmedIntentChange: (String) -> Unit,
    outcomeTitle: String,
    onOutcomeTitleChange: (String) -> Unit,
    onPlanRemediation: (ReportItemResponse) -> Unit,
    onPromptForOutcome: () -> Unit,
    onCreateOutcome: () -> Unit,
    onAdmitDirection: () -> Unit,
    onSubscribe: (String) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    conversation: ConversationProjectionResponse?,
    conversationPrompt: String,
    conversationError: String?,
    isConversationSubmitting: Boolean,
    onConversationPromptChange: (String) -> Unit,
    onSubmitConversation: () -> Unit,
    onAdmitCommand: (ConversationCommandViewResponse) -> Unit,
    onRejectCommand: (ConversationCommandViewResponse) -> Unit,
    onControlObjective: (ConversationObjectiveResponse, String, Int?, List<Long>?) -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(report.report.title, color = InboxInk, fontWeight = FontWeight.Bold, fontSize = 21.sp)
                Text("${report.report.scope.type.replace('_', ' ')} ${report.report.scope.targetId}  |  revision ${report.revision.revision}  |  ${report.revision.state}", Modifier.padding(top = 5.dp), color = InboxMuted, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
            }
        }
        if (outcomeAction != null) {
            Divider(Modifier.padding(top = 18.dp), color = InboxLine)
            Text("FIRST OUTCOME", Modifier.padding(top = 16.dp), color = InboxGreen, fontWeight = FontWeight.Bold, fontSize = 9.sp)
            if (outcomeAction.type == FirstOutcomeActionType.CREATE) {
                OutlinedTextField(
                    value = confirmedIntent,
                    onValueChange = onConfirmedIntentChange,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    label = { Text("Confirmed current product intent") },
                    enabled = !isMutating,
                    minLines = 2,
                    maxLines = 4,
                )
                OutlinedTextField(
                    value = outcomeTitle,
                    onValueChange = onOutcomeTitleChange,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    label = { Text("Desired outcome") },
                    enabled = !isMutating,
                    singleLine = true,
                )
                TextButton(onClick = onCreateOutcome, enabled = !isMutating && confirmedIntent.isNotBlank() && outcomeTitle.isNotBlank()) {
                    Text(outcomeAction.label)
                }
            } else if (outcomeAction.type == FirstOutcomeActionType.ADMIT) {
                Text(
                    "Your confirmed intent and first outcome are ready to become project authority. Architecture and verification remain deferred to governed delivery.",
                    Modifier.padding(top = 6.dp),
                    color = InboxMuted,
                    fontSize = 11.sp,
                )
                TextButton(onClick = onAdmitDirection, enabled = !isMutating) { Text(outcomeAction.label) }
            } else {
                Text("Continue in this report's canonical thread so the conductor can preserve authority and context.", Modifier.padding(top = 6.dp), color = InboxMuted, fontSize = 11.sp)
                TextButton(onClick = onPromptForOutcome, enabled = !isMutating) { Text(outcomeAction.label) }
            }
        }
        val structuredItems = report.items.filter { it.kind != "CONVERSATION" }
        if (structuredItems.isNotEmpty()) {
            Divider(Modifier.padding(top = 14.dp), color = InboxLine)
            Text("REPORT ITEMS", Modifier.padding(top = 16.dp), color = InboxMuted, fontWeight = FontWeight.Bold, fontSize = 9.sp)
        }
        structuredItems.forEach { item ->
            Column(Modifier.fillMaxWidth().padding(vertical = 13.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.title, Modifier.weight(1f), color = InboxInk, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text(item.state.replace('_', ' '), color = if (item.actionRequired) InboxAmber else InboxGreen, fontWeight = FontWeight.Bold, fontSize = 9.sp)
                }
                Text(item.summary, Modifier.padding(top = 5.dp), color = InboxMuted, fontSize = 11.sp)
                item.diagnosis?.let { diagnosis ->
                    Text(
                        diagnosis.category.replace('_', ' '),
                        Modifier.padding(top = 9.dp),
                        color = InboxAmber,
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp,
                    )
                    Text(diagnosis.impact, Modifier.padding(top = 3.dp), color = InboxInk, fontSize = 11.sp)
                    diagnosis.suggestedEvidence.forEach { suggestion ->
                        Row(Modifier.padding(top = 5.dp), verticalAlignment = Alignment.Top) {
                            Icon(Icons.Default.Check, null, Modifier.size(13.dp), tint = InboxGreen)
                            Spacer(Modifier.width(6.dp))
                            Text(suggestion, Modifier.weight(1f), color = InboxMuted, fontSize = 10.sp)
                        }
                    }
                }
                item.evidence.forEach { evidence ->
                    Text("${evidence.type}  ${evidence.identity}${evidence.revision.takeIf(String::isNotBlank)?.let { "@$it" }.orEmpty()}", Modifier.padding(top = 7.dp), color = InboxBlue, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                    if (evidence.description.isNotBlank()) Text(evidence.description, Modifier.padding(top = 2.dp), color = InboxMuted, fontSize = 10.sp)
                }
                item.remediation?.let { remediation ->
                    TextButton(
                        onClick = { onPlanRemediation(item) },
                        enabled = !isMutating,
                        modifier = Modifier.padding(top = 5.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Chat, null, Modifier.size(15.dp), tint = InboxGreen)
                        Spacer(Modifier.width(6.dp))
                        Text(remediation.label, color = InboxGreen, fontSize = 10.sp)
                    }
                }
            }
            Divider(color = InboxLine)
        }
        Text("SUBSCRIPTION", Modifier.padding(top = 18.dp), color = InboxMuted, fontWeight = FontWeight.Bold, fontSize = 9.sp)
        val subscription = report.subscription
        if (subscription == null) {
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("IMPORTANT", "MILESTONES", "ACTION_REQUIRED", "ALL").forEach { mode ->
                    TextButton(onClick = { onSubscribe(mode) }, enabled = !isMutating) { Text(mode.lowercase().replace('_', ' '), fontSize = 10.sp) }
                }
            }
        } else {
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${subscription.mode.replace('_', ' ')}  |  ${subscription.state}", color = InboxInk, fontSize = 11.sp)
                Spacer(Modifier.width(8.dp))
                if (subscription.state == "PAUSED") {
                    TextButton(onClick = onResume, enabled = !isMutating) {
                        Icon(Icons.Default.PlayArrow, null, Modifier.size(15.dp))
                        Text("Resume")
                    }
                } else {
                    TextButton(onClick = onPause, enabled = !isMutating) {
                        Icon(Icons.Default.Pause, null, Modifier.size(15.dp))
                        Text("Pause")
                    }
                }
            }
        }
        Divider(Modifier.padding(top = 16.dp), color = InboxLine)
        InlineInboxConversation(
            conversation = conversation,
            prompt = conversationPrompt,
            error = conversationError,
            isSubmitting = isConversationSubmitting,
            onPromptChange = onConversationPromptChange,
            onSubmit = onSubmitConversation,
            onAdmitCommand = onAdmitCommand,
            onRejectCommand = onRejectCommand,
            onControlObjective = onControlObjective,
        )
    }
}

@Composable
private fun InlineInboxConversation(
    conversation: ConversationProjectionResponse?,
    prompt: String,
    error: String?,
    isSubmitting: Boolean,
    onPromptChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onAdmitCommand: (ConversationCommandViewResponse) -> Unit,
    onRejectCommand: (ConversationCommandViewResponse) -> Unit,
    onControlObjective: (ConversationObjectiveResponse, String, Int?, List<Long>?) -> Unit,
) {
    Text("CONVERSATION", Modifier.padding(top = 18.dp), color = InboxMuted, fontWeight = FontWeight.Bold, fontSize = 9.sp)
    if (conversation == null) {
        Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalArrangement = Arrangement.Center) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = InboxGreen)
        }
        return
    }
    conversation.messages.forEach { message ->
        val user = message.role == "USER"
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = if (user) Arrangement.End else Arrangement.Start,
        ) {
            Column(Modifier.fillMaxWidth(0.82f)) {
                Text(
                    if (user) "You" else "Orchard",
                    color = if (user) InboxBlue else InboxGreen,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 9.sp,
                )
                Surface(
                    modifier = Modifier.padding(top = 4.dp),
                    color = if (user) InboxBlue else InboxListSurface,
                    shape = RoundedCornerShape(6.dp),
                    border = if (user) null else BorderStroke(1.dp, InboxLine),
                ) {
                    Text(
                        message.content,
                        Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        color = if (user) Color.White else InboxInk,
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
    conversation.objectives.forEach { objective ->
        Column(
            Modifier.fillMaxWidth().padding(top = 12.dp).background(InboxGreenSoft).padding(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(objective.title, color = InboxInk, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                    Text(objective.state.replace('_', ' '), Modifier.padding(top = 2.dp), color = InboxMuted, fontSize = 9.sp)
                }
            }
            ObjectiveControls(objective, conversation.objectives, onControlObjective)
        }
    }
    conversation.commands.filter { it.admission == null && it.executions.isEmpty() }.forEach { command ->
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp).background(InboxAmberSoft).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(command.proposal.capabilityId.replace('_', ' '), color = InboxInk, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                Text("Exact command admission required", Modifier.padding(top = 2.dp), color = InboxAmber, fontSize = 9.sp)
                Text(command.proposal.payloadJson, Modifier.padding(top = 5.dp), color = InboxMuted, fontFamily = FontFamily.Monospace, fontSize = 9.sp, maxLines = 5, overflow = TextOverflow.Ellipsis)
                Text("command ${command.proposal.commandId}  |  ${command.proposal.hash}", Modifier.padding(top = 5.dp), color = InboxMuted, fontFamily = FontFamily.Monospace, fontSize = 8.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = { onRejectCommand(command) }, enabled = !isSubmitting) { Text("Reject", fontSize = 10.sp) }
            TextButton(onClick = { onAdmitCommand(command) }, enabled = !isSubmitting) { Text("Admit", fontSize = 10.sp) }
        }
    }
    if (!error.isNullOrBlank()) {
        Text(error, Modifier.fillMaxWidth().padding(top = 10.dp).background(InboxAmberSoft).padding(8.dp), color = InboxAmber, fontSize = 10.sp)
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = prompt,
            onValueChange = onPromptChange,
            modifier = Modifier.weight(1f).heightIn(min = 52.dp, max = 120.dp),
            placeholder = { Text("Reply in this conversation", fontSize = 11.sp) },
            enabled = !isSubmitting,
            minLines = 2,
            maxLines = 5,
        )
        IconButton(
            onClick = onSubmit,
            enabled = prompt.isNotBlank() && !isSubmitting,
            modifier = Modifier.size(38.dp),
        ) {
            if (isSubmitting) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = InboxGreen)
            else Icon(Icons.AutoMirrored.Filled.Send, "Send reply", Modifier.size(18.dp), tint = InboxGreen)
        }
    }
}

@Composable
private fun NewConversationDialog(
    projectId: Int,
    isSubmitting: Boolean,
    onDismiss: () -> Unit,
    onCreate: (String, String, String, String, String, String?) -> Unit,
) {
    val clientRequestId = remember { UUID.randomUUID().toString() }
    var title by remember { mutableStateOf("") }
    var summary by remember { mutableStateOf("") }
    var scopeType by remember { mutableStateOf("PROJECT") }
    var targetId by remember(projectId) { mutableStateOf(projectId.toString()) }
    var subscriptionMode by remember { mutableStateOf<String?>("IMPORTANT") }
    var scopeExpanded by remember { mutableStateOf(false) }
    var subscriptionExpanded by remember { mutableStateOf(false) }
    val requiresTarget = scopeType != "PROJECT"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New conversation") },
        text = {
            Column(Modifier.width(520.dp).heightIn(max = 600.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Subject") }, enabled = !isSubmitting, singleLine = true)
                OutlinedTextField(summary, { summary = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Message") }, enabled = !isSubmitting, minLines = 3, maxLines = 6)
                Box(Modifier.padding(top = 8.dp)) {
                    TextButton(onClick = { scopeExpanded = true }, enabled = !isSubmitting) {
                        Text("Scope: ${scopeType.replace('_', ' ').lowercase()}")
                        Icon(Icons.Default.KeyboardArrowDown, null)
                    }
                    DropdownMenu(scopeExpanded, { scopeExpanded = false }) {
                        listOf("PROJECT", "TICKET", "OUTCOME", "CAPABILITY", "REPOSITORY_AREA").forEach { type ->
                            DropdownMenuItem(onClick = {
                                scopeType = type
                                targetId = if (type == "PROJECT") projectId.toString() else ""
                                scopeExpanded = false
                            }) { Text(type.replace('_', ' ').lowercase().replaceFirstChar(Char::uppercase)) }
                        }
                    }
                }
                if (requiresTarget) {
                    OutlinedTextField(targetId, { targetId = it }, Modifier.fillMaxWidth(), label = { Text(if (scopeType in setOf("TICKET", "OUTCOME")) "Workspace ticket ID" else "Scope identifier") }, enabled = !isSubmitting, singleLine = true)
                }
                Box(Modifier.padding(top = 4.dp)) {
                    TextButton(onClick = { subscriptionExpanded = true }, enabled = !isSubmitting) {
                        Text("Subscription: ${subscriptionMode?.replace('_', ' ')?.lowercase() ?: "none"}")
                        Icon(Icons.Default.KeyboardArrowDown, null)
                    }
                    DropdownMenu(subscriptionExpanded, { subscriptionExpanded = false }) {
                        listOf<String?>(null, "IMPORTANT", "MILESTONES", "ACTION_REQUIRED", "ALL").forEach { mode ->
                            DropdownMenuItem(onClick = { subscriptionMode = mode; subscriptionExpanded = false }) {
                                if (mode == subscriptionMode) Icon(Icons.Default.Check, null, Modifier.size(15.dp)) else Spacer(Modifier.size(15.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(mode?.replace('_', ' ')?.lowercase()?.replaceFirstChar(Char::uppercase) ?: "None")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(clientRequestId, title.trim(), summary.trim(), scopeType, targetId.trim(), subscriptionMode) }, enabled = !isSubmitting && title.isNotBlank() && summary.isNotBlank() && targetId.isNotBlank()) {
                if (isSubmitting) CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp) else Text("Start conversation")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isSubmitting) { Text("Cancel") } },
        shape = RoundedCornerShape(8.dp),
        backgroundColor = InboxSurface,
    )
}

private fun inboxActionValue(action: String, key: String): Int {
    val marker = "$key="
    val start = action.indexOf(marker)
    if (start == -1) return 0
    val valueStart = start + marker.length
    val valueEnd = action.indexOf(';', valueStart).let { if (it == -1) action.length else it }
    return action.substring(valueStart, valueEnd).toIntOrNull() ?: 0
}

private fun String.inboxTimestamp(): String = take(16).replace('T', ' ')

private val InboxSurface = Color(0xFFFFFFFF)
private val InboxListSurface = Color(0xFFFAFAF8)
private val InboxSelected = Color(0xFFF0F4EA)
private val InboxLine = Color(0xFFE1E2DD)
private val InboxInk = Color(0xFF252724)
private val InboxMuted = Color(0xFF6D716B)
private val InboxGreen = Color(0xFF52713F)
private val InboxGreenSoft = Color(0xFFE8EFE1)
private val InboxBlue = Color(0xFF326FA0)
private val InboxAmber = Color(0xFF936516)
private val InboxAmberSoft = Color(0xFFFAF2E3)
private val InboxRed = Color(0xFFAA4B45)