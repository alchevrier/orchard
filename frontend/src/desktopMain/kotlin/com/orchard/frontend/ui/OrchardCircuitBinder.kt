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
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
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
import java.io.File
import javax.swing.JFileChooser
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
)

class OrchardCircuitBinder(private val networkClient: DesktopNetworkClient) {
    private val requestMutex = Mutex()
    private var response by mutableStateOf(WorkspaceSnapshotResponse())

    @Composable
    fun render() {
        var prompt by remember { mutableStateOf("") }
        var isSubmitting by remember { mutableStateOf(false) }
        var isBindingRepository by remember { mutableStateOf(false) }
        var startingWorkItemId by remember { mutableStateOf(0) }
        var cancellingRunId by remember { mutableStateOf(0L) }
        var definingWorkItemId by remember { mutableStateOf(0) }
        var isSubmittingDefinition by remember { mutableStateOf(false) }
        var requestError by remember { mutableStateOf<String?>(null) }
        var selectedProjectId by remember { mutableStateOf(0) }
        var selectedEpicId by remember { mutableStateOf(0) }
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
                    onRefresh = {
                        scope.launch {
                            requestError = null
                            refreshWorkspace().onFailure {
                                requestError = it.message ?: "Unable to refresh the workspace."
                            }
                        }
                    },
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
                WorkDefinitionDialog(
                    workItem = workItem,
                    current = snapshot.workDefinitions.firstOrNull { it.workItemId == workItem.id },
                    isSubmitting = isSubmittingDefinition,
                    onDismiss = { if (!isSubmittingDefinition) definingWorkItemId = 0 },
                    onSubmit = { definition ->
                        if (!isSubmittingDefinition) {
                            isSubmittingDefinition = true
                            requestError = null
                            scope.launch {
                                submitWorkDefinition(workItem.id, definition)
                                    .onSuccess { definingWorkItemId = 0 }
                                    .onFailure { requestError = it.message ?: "Unable to assess the work definition." }
                                isSubmittingDefinition = false
                            }
                        }
                    },
                )
            }
        }
    }

    private suspend fun refreshWorkspace(): Result<Unit> = executeRequest {
        networkClient.getWorkspace()
    }

    private suspend fun sendArchitectPrompt(prompt: String): Result<Unit> = executeRequest {
        networkClient.submitArchitectPrompt(prompt)
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

    private suspend fun cancelWorkflow(runId: Long): Result<Unit> = executeRequest {
        networkClient.cancelWorkflow(runId)
    }

    private suspend fun executeRequest(request: suspend () -> WorkspaceSnapshotResponse): Result<Unit> {
        return requestMutex.withLock {
            runCatching {
                response = request()
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
    onSelectEpic: (Int) -> Unit,
    onBindRepository: () -> Unit,
    onStartWorkflow: (Int) -> Unit,
    onCancelWorkflow: (Long) -> Unit,
    onDefineWork: (Int) -> Unit,
    onRefresh: () -> Unit,
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
            IconButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh workspace", tint = OrchardColors.muted)
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
                        startingWorkItemId = startingWorkItemId,
                        onStartWorkflow = onStartWorkflow,
                        cancellingRunId = cancellingRunId,
                        onCancelWorkflow = onCancelWorkflow,
                        onDefineWork = onDefineWork,
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

@Composable
private fun StoryBoard(
    story: WorkspaceEntity,
    tickets: List<WorkspaceEntity>,
    workflowRuns: List<WorkflowRunResponse>,
    workDefinitions: List<WorkDefinitionResponse>,
    startingWorkItemId: Int,
    onStartWorkflow: (Int) -> Unit,
    cancellingRunId: Long,
    onCancelWorkflow: (Long) -> Unit,
    onDefineWork: (Int) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().background(OrchardColors.storyBand, RoundedCornerShape(8.dp)).padding(18.dp),
    ) {
        Text(story.title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = OrchardColors.ink)
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
                            workflowRun = workflowRuns.firstOrNull { it.context.workItemId == ticket.id },
                            workDefinition = workDefinitions.firstOrNull { it.workItemId == ticket.id },
                            isStarting = startingWorkItemId == ticket.id,
                            onStartWorkflow = { onStartWorkflow(ticket.id) },
                            isCancelling = workflowRuns.firstOrNull { it.context.workItemId == ticket.id }?.runId == cancellingRunId,
                            onCancelWorkflow = { runId -> onCancelWorkflow(runId) },
                            onDefineWork = { onDefineWork(ticket.id) },
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
    isStarting: Boolean,
    onStartWorkflow: () -> Unit,
    isCancelling: Boolean,
    onCancelWorkflow: (Long) -> Unit,
    onDefineWork: () -> Unit,
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
                TextButton(
                    onClick = onDefineWork,
                    modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 3.dp),
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null, tint = definitionColor, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (workDefinition == null) "Define work" else "Revise definition", fontSize = 11.sp, color = definitionColor)
                }
                if (definitionState == "READY") {
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
            }
        }
    }
}

@Composable
private fun WorkDefinitionDialog(
    workItem: WorkspaceEntity,
    current: WorkDefinitionResponse?,
    isSubmitting: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (WorkDefinitionSubmissionRequest) -> Unit,
) {
    val existing = current?.definition
    var requestedOutcome by remember(workItem.id, current?.hash) { mutableStateOf(existing?.requestedOutcome.orEmpty()) }
    var currentBehavior by remember(workItem.id, current?.hash) { mutableStateOf(existing?.currentBehavior.orEmpty()) }
    var requiredBehavior by remember(workItem.id, current?.hash) { mutableStateOf(existing?.requiredBehavior.orEmpty()) }
    var scope by remember(workItem.id, current?.hash) { mutableStateOf(existing?.scope.orEmpty().joinToString("\n")) }
    var nonGoals by remember(workItem.id, current?.hash) { mutableStateOf(existing?.nonGoals.orEmpty().joinToString("\n")) }
    var constraints by remember(workItem.id, current?.hash) { mutableStateOf(existing?.constraints.orEmpty().joinToString("\n")) }
    var criteria by remember(workItem.id, current?.hash) {
        mutableStateOf(existing?.acceptanceCriteria.orEmpty().joinToString("\n") { it.description })
    }
    var verification by remember(workItem.id, current?.hash) {
        mutableStateOf(existing?.acceptanceCriteria.orEmpty().joinToString("\n") { it.verification })
    }
    var questions by remember(workItem.id, current?.hash) { mutableStateOf(existing?.unresolvedQuestions.orEmpty().joinToString("\n")) }
    var splits by remember(workItem.id, current?.hash) { mutableStateOf(existing?.proposedSplitTitles.orEmpty().joinToString("\n")) }
    var reproduction by remember(workItem.id, current?.hash) { mutableStateOf(existing?.reproduction.orEmpty()) }
    var regression by remember(workItem.id, current?.hash) { mutableStateOf(existing?.regressionCriterion.orEmpty()) }

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
                DefinitionField("Requested outcome", requestedOutcome) { requestedOutcome = it }
                DefinitionField("Current behavior", currentBehavior) { currentBehavior = it }
                DefinitionField("Required behavior", requiredBehavior) { requiredBehavior = it }
                DefinitionField("Scope (one per line)", scope) { scope = it }
                DefinitionField("Non-goals (one per line)", nonGoals) { nonGoals = it }
                DefinitionField("Constraints (one per line)", constraints) { constraints = it }
                DefinitionField("Acceptance criteria (one per line)", criteria) { criteria = it }
                DefinitionField("Verification for each criterion (one per line)", verification) { verification = it }
                if (workItem.type == BUG) {
                    DefinitionField("Reproduction", reproduction) { reproduction = it }
                    DefinitionField("Regression criterion", regression) { regression = it }
                }
                DefinitionField("Unresolved questions (one per line)", questions) { questions = it }
                DefinitionField("Proposed split titles (one per line)", splits) { splits = it }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !isSubmitting,
                onClick = {
                    val descriptions = lines(criteria)
                    val verifications = lines(verification)
                    onSubmit(
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
                else Text("Assess definition")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isSubmitting) { Text("Cancel") } },
        shape = RoundedCornerShape(8.dp),
        backgroundColor = OrchardColors.surface,
    )
}

@Composable
private fun DefinitionField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
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