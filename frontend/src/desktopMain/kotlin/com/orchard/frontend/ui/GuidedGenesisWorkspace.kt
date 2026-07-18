package com.orchard.frontend.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Checkbox
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orchard.frontend.network.ArchitectureComponentRequest
import com.orchard.frontend.network.ArchitectureDecisionRequest
import com.orchard.frontend.network.ExperienceContractRequest
import com.orchard.frontend.network.GenesisProposalResponse
import com.orchard.frontend.network.ProjectGenesisSubmissionRequest
import com.orchard.frontend.network.ProjectGenesisViewResponse
import com.orchard.frontend.network.RepositoryBlueprintRequest
import com.orchard.frontend.network.CompanyProjectResponse
import com.orchard.frontend.network.EngineeringPracticeResponse
import com.orchard.frontend.network.EngineeringStandardSubmissionRequest
import com.orchard.frontend.network.EngineeringStandardsViewResponse
import com.orchard.frontend.network.RemediationCampaignViewResponse
import com.orchard.frontend.network.CampaignResolutionViewResponse
import com.orchard.frontend.network.RepositoryExecutionPlanResponse

private const val GENESIS_CLASSIFICATION = "CLASSIFICATION"
private const val GENESIS_EXPERIENCE = "EXPERIENCE"
private const val GENESIS_ARCHITECTURE = "ARCHITECTURE"
private const val GENESIS_BLUEPRINT = "BLUEPRINT"
private const val GENESIS_ADMISSION = "ADMISSION"
private const val GENESIS_READY = "READY"

private val GenesisCanvas = Color(0xFFF5F5F3)
private val GenesisSurface = Color(0xFFFCFCFB)
private val GenesisInk = Color(0xFF171918)
private val GenesisMuted = Color(0xFF6E7370)
private val GenesisLine = Color(0xFFDADDD9)
private val GenesisGreen = Color(0xFF2D6A4F)
private val GenesisGreenSoft = Color(0xFFE1EDE7)
private val GenesisBlue = Color(0xFF315F8C)
private val GenesisAmber = Color(0xFF9A681C)
private val GenesisRed = Color(0xFFA1463F)

private val GenesisStages = listOf(
    GENESIS_CLASSIFICATION to "Context",
    GENESIS_EXPERIENCE to "Experience",
    GENESIS_ARCHITECTURE to "Architecture",
    GENESIS_BLUEPRINT to "Repository",
    GENESIS_ADMISSION to "Admission",
    GENESIS_READY to "Ready",
)

@Composable
internal fun GuidedGenesisWorkspace(
    projectId: Int,
    projectTitle: String,
    epics: List<Pair<Int, String>>,
    genesis: ProjectGenesisViewResponse?,
    repositoryPath: String?,
    repositoryAvailable: Boolean,
    message: String,
    isSubmitting: Boolean,
    isBindingRepository: Boolean,
    isGeneratingProposal: Boolean,
    proposal: GenesisProposalResponse?,
    company: CompanyProjectResponse?,
    executionPlan: RepositoryExecutionPlanResponse?,
    engineeringStandards: EngineeringStandardsViewResponse?,
    remediationCampaigns: List<RemediationCampaignViewResponse>,
    campaignResolutions: List<CampaignResolutionViewResponse>,
    isSavingStandard: Boolean,
    isScanningConformance: Boolean,
    isAdmittingConformance: Boolean,
    isResolvingCampaign: Boolean,
    onCreateProject: (String) -> Unit,
    onCreateEpic: (String) -> Unit,
    onBindRepository: () -> Unit,
    onGenerateProposal: (String) -> Unit,
    onApplyProposal: () -> Unit,
    onAdvance: (ProjectGenesisSubmissionRequest) -> Unit,
    onAdmit: () -> Unit,
    onStartCompany: () -> Unit,
    onPromote: (Long) -> Unit,
    onSaveStandard: (EngineeringStandardSubmissionRequest) -> Unit,
    onScanConformance: () -> Unit,
    onAdmitConformance: (Long) -> Unit,
    onProposeCampaignResolution: (Long) -> Unit,
    onAdmitCampaignResolution: (Long) -> Unit,
    onRefresh: () -> Unit,
) {
    val phase = genesis?.phase ?: GENESIS_CLASSIFICATION
    val progress by animateFloatAsState(
        targetValue = (genesis?.progress ?: 0) / 100f,
        animationSpec = tween(420, easing = FastOutSlowInEasing),
    )

    Column(Modifier.fillMaxSize().background(GenesisCanvas)) {
        GenesisHeader(projectTitle, phase, genesis?.revision?.revision, onRefresh)
        LinearProgressIndicator(
            progress = progress,
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = GenesisGreen,
            backgroundColor = GenesisLine,
        )
        ProgressSpine(phase)
        Divider(color = GenesisLine)
        Row(Modifier.fillMaxSize()) {
            ConversationSurface(
                modifier = Modifier.weight(0.42f).fillMaxHeight(),
                projectId = projectId,
                phase = phase,
                genesis = genesis,
                epics = epics,
                repositoryPath = repositoryPath,
                repositoryAvailable = repositoryAvailable,
                message = message,
                isSubmitting = isSubmitting,
                isBindingRepository = isBindingRepository,
                isGeneratingProposal = isGeneratingProposal,
                proposal = proposal,
                company = company,
                executionPlan = executionPlan,
                onCreateProject = onCreateProject,
                onCreateEpic = onCreateEpic,
                onBindRepository = onBindRepository,
                onGenerateProposal = onGenerateProposal,
                onApplyProposal = onApplyProposal,
                onAdvance = onAdvance,
                onAdmit = onAdmit,
                onStartCompany = onStartCompany,
                onPromote = onPromote,
            )
            Divider(Modifier.fillMaxHeight().width(1.dp), color = GenesisLine)
            ProjectionSurface(
                modifier = Modifier.weight(0.58f).fillMaxHeight(),
                phase = phase,
                genesis = genesis,
                company = company,
                executionPlan = executionPlan,
                engineeringStandards = engineeringStandards,
                remediationCampaigns = remediationCampaigns,
                campaignResolutions = campaignResolutions,
                isSavingStandard = isSavingStandard,
                isScanningConformance = isScanningConformance,
                isAdmittingConformance = isAdmittingConformance,
                isResolvingCampaign = isResolvingCampaign,
                onSaveStandard = onSaveStandard,
                onScanConformance = onScanConformance,
                onAdmitConformance = onAdmitConformance,
                onProposeCampaignResolution = onProposeCampaignResolution,
                onAdmitCampaignResolution = onAdmitCampaignResolution,
            )
        }
    }
}

@Composable
private fun GenesisHeader(
    projectTitle: String,
    phase: String,
    revision: Int?,
    onRefresh: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(68.dp).background(GenesisSurface).padding(horizontal = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("ORCHARD", fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = GenesisInk)
        Divider(Modifier.padding(horizontal = 18.dp).height(22.dp).width(1.dp), color = GenesisLine)
        Column(Modifier.weight(1f)) {
            Text(projectTitle.ifBlank { "New product" }, color = GenesisInk, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(
                if (revision == null) "Product genesis" else "Product genesis  /  revision $revision",
                color = GenesisMuted,
                fontSize = 11.sp,
            )
        }
        StatusPill(phase)
        IconButton(onClick = onRefresh, modifier = Modifier.padding(start = 10.dp).size(36.dp)) {
            Icon(Icons.Default.Refresh, contentDescription = "Refresh state", tint = GenesisMuted, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun StatusPill(phase: String) {
    val ready = phase == GENESIS_READY
    Row(
        Modifier.background(if (ready) GenesisGreenSoft else Color(0xFFE9ECEA), RoundedCornerShape(8.dp))
            .padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).background(if (ready) GenesisGreen else GenesisBlue, CircleShape))
        Text(
            if (ready) "Authority admitted" else phase.lowercase().replaceFirstChar(Char::uppercase),
            modifier = Modifier.padding(start = 7.dp),
            color = if (ready) GenesisGreen else GenesisInk,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun ProgressSpine(phase: String) {
    val activeIndex = GenesisStages.indexOfFirst { it.first == phase }.coerceAtLeast(0)
    Row(
        Modifier.fillMaxWidth().height(58.dp).background(GenesisSurface).padding(horizontal = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GenesisStages.forEachIndexed { index, (_, label) ->
            val complete = index < activeIndex || phase == GENESIS_READY
            val active = index == activeIndex && phase != GENESIS_READY
            Box(
                Modifier.size(20.dp).background(
                    when {
                        complete -> GenesisGreen
                        active -> GenesisInk
                        else -> GenesisLine
                    },
                    CircleShape,
                ),
                contentAlignment = Alignment.Center,
            ) {
                if (complete) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(12.dp))
                else Text((index + 1).toString(), color = if (active) Color.White else GenesisMuted, fontSize = 9.sp)
            }
            Text(
                label,
                modifier = Modifier.padding(start = 7.dp),
                color = if (index <= activeIndex) GenesisInk else GenesisMuted,
                fontSize = 11.sp,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            )
            if (index != GenesisStages.lastIndex) {
                Box(Modifier.weight(1f).padding(horizontal = 11.dp).height(1.dp).background(if (index < activeIndex) GenesisGreen else GenesisLine))
            }
        }
    }
}

@Composable
private fun ConversationSurface(
    modifier: Modifier,
    projectId: Int,
    phase: String,
    genesis: ProjectGenesisViewResponse?,
    epics: List<Pair<Int, String>>,
    repositoryPath: String?,
    repositoryAvailable: Boolean,
    message: String,
    isSubmitting: Boolean,
    isBindingRepository: Boolean,
    isGeneratingProposal: Boolean,
    proposal: GenesisProposalResponse?,
    company: CompanyProjectResponse?,
    executionPlan: RepositoryExecutionPlanResponse?,
    onCreateProject: (String) -> Unit,
    onCreateEpic: (String) -> Unit,
    onBindRepository: () -> Unit,
    onGenerateProposal: (String) -> Unit,
    onApplyProposal: () -> Unit,
    onAdvance: (ProjectGenesisSubmissionRequest) -> Unit,
    onAdmit: () -> Unit,
    onStartCompany: () -> Unit,
    onPromote: (Long) -> Unit,
) {
    Column(modifier.background(GenesisSurface).padding(30.dp)) {
        Text("ARCHITECT", color = GenesisGreen, fontWeight = FontWeight.Bold, fontSize = 10.sp)
        Text(
            genesis?.nextQuestion ?: if (projectId == 0) {
                "What are you setting out to make?"
            } else {
                "Is this a new local product, existing local work, or organization-governed work?"
            },
            modifier = Modifier.padding(top = 12.dp),
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Medium,
            fontSize = 25.sp,
            lineHeight = 32.sp,
            color = GenesisInk,
        )
        Text(
            genesis?.blockingReason ?: message,
            modifier = Modifier.padding(top = 12.dp, bottom = 22.dp),
            color = if (genesis?.blockingReason == null) GenesisMuted else GenesisAmber,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
        Divider(color = GenesisLine)
        if (genesis?.revision?.classification == "EXISTING_LOCAL") {
            RepositoryAuthority(
                repositoryPath = repositoryPath,
                available = repositoryAvailable,
                loading = isBindingRepository,
                onBind = onBindRepository,
            )
        }
        if (projectId != 0 && phase !in setOf(GENESIS_ADMISSION, GENESIS_READY)) {
            GenesisComposer(
                phase = phase,
                loading = isGeneratingProposal,
                proposal = proposal,
                onGenerate = onGenerateProposal,
                onApply = onApplyProposal,
            )
        }
        AnimatedContent(
            targetState = phase,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 18.dp),
            transitionSpec = {
                (fadeIn(tween(260)) + slideInHorizontally(tween(260)) { it / 10 }) togetherWith
                    (fadeOut(tween(180)) + slideOutHorizontally(tween(180)) { -it / 12 })
            },
            label = "genesis-phase",
        ) { targetPhase ->
            Box(Modifier.fillMaxSize()) {
                when {
                    projectId == 0 -> NewProjectConversation(isSubmitting, onCreateProject)
                    targetPhase == GENESIS_CLASSIFICATION -> ClassificationConversation(genesis, isSubmitting, onAdvance)
                    targetPhase == GENESIS_EXPERIENCE -> ExperienceConversation(genesis, isSubmitting, onAdvance)
                    targetPhase == GENESIS_ARCHITECTURE -> ArchitectureConversation(
                        genesis,
                        epics,
                        isSubmitting,
                        onCreateEpic,
                        onAdvance,
                    )
                    targetPhase == GENESIS_BLUEPRINT -> BlueprintConversation(genesis, isSubmitting, onAdvance)
                    targetPhase == GENESIS_ADMISSION -> AdmissionConversation(genesis, isSubmitting, onAdmit)
                    else -> ReadyConversation(company, isSubmitting, onStartCompany, onPromote)
                }
            }
        }
    }
}

@Composable
private fun GenesisComposer(
    phase: String,
    loading: Boolean,
    proposal: GenesisProposalResponse?,
    onGenerate: (String) -> Unit,
    onApply: () -> Unit,
) {
    var prompt by remember(phase) { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                placeholder = { Text("Talk through this decision...", fontSize = 11.sp) },
                modifier = Modifier.weight(1f),
                minLines = 2,
                maxLines = 4,
                shape = RoundedCornerShape(8.dp),
                colors = androidx.compose.material.TextFieldDefaults.outlinedTextFieldColors(
                    focusedBorderColor = GenesisGreen,
                    unfocusedBorderColor = GenesisLine,
                    cursorColor = GenesisGreen,
                    backgroundColor = Color.White,
                    textColor = GenesisInk,
                ),
                textStyle = MaterialTheme.typography.body2.copy(fontSize = 11.sp, lineHeight = 16.sp),
            )
            IconButton(
                onClick = { onGenerate(prompt.trim()) },
                enabled = prompt.isNotBlank() && !loading,
                modifier = Modifier.padding(start = 8.dp).size(40.dp).background(GenesisInk, CircleShape),
            ) {
                if (loading) CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp, color = Color.White)
                else Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Ask Genesis Architect", tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
        AnimatedVisibility(proposal != null, enter = fadeIn(tween(240)), exit = fadeOut(tween(160))) {
            proposal?.let { candidate ->
                Column(
                    Modifier.fillMaxWidth().padding(top = 10.dp).background(GenesisGreenSoft, RoundedCornerShape(8.dp))
                        .padding(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("GENESIS ARCHITECT PROPOSAL", color = GenesisGreen, fontWeight = FontWeight.Bold, fontSize = 9.sp, modifier = Modifier.weight(1f))
                        Text(candidate.model, color = GenesisMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    }
                    candidate.observations.take(2).forEach {
                        Text(it, color = GenesisInk, fontSize = 10.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                    candidate.unresolvedQuestions.take(2).forEach {
                        Text("Open: $it", color = GenesisAmber, fontSize = 10.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 5.dp))
                    }
                    TextButton(
                        onClick = onApply,
                        modifier = Modifier.align(Alignment.End).padding(top = 4.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 9.dp, vertical = 5.dp),
                    ) {
                        Text("Apply proposal", color = GenesisGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Divider(Modifier.weight(1f), color = GenesisLine)
            Text("or resolve precisely", Modifier.padding(horizontal = 10.dp), color = GenesisMuted, fontSize = 9.sp)
            Divider(Modifier.weight(1f), color = GenesisLine)
        }
    }
}

@Composable
private fun RepositoryAuthority(
    repositoryPath: String?,
    available: Boolean,
    loading: Boolean,
    onBind: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 14.dp).background(Color(0xFFF0F2F1), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.FolderOpen, null, tint = if (available) GenesisGreen else GenesisAmber, modifier = Modifier.size(17.dp))
        Column(Modifier.weight(1f).padding(horizontal = 9.dp)) {
            Text(if (available) "Existing repository bound" else "Repository authority required", color = GenesisInk, fontWeight = FontWeight.Bold, fontSize = 10.sp)
            Text(repositoryPath ?: "Select the local Git repository this design must respect.", color = GenesisMuted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TextButton(onClick = onBind, enabled = !loading, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
            if (loading) CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp, color = GenesisGreen)
            else Text(if (repositoryPath == null) "Choose" else "Change", color = GenesisGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun NewProjectConversation(isSubmitting: Boolean, onCreateProject: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        GuidedField("Product name", name, { name = it }, minLines = 2)
        Text(
            "Orchard will create only the project shell. Its experience, first vertical slice, architecture, and repository remain unresolved until you design them here.",
            color = GenesisMuted,
            fontSize = 11.sp,
            lineHeight = 17.sp,
        )
        Spacer(Modifier.weight(1f))
        PrimaryAction("Begin product genesis", isSubmitting, name.isNotBlank()) { onCreateProject(name.trim()) }
    }
}

@Composable
private fun ClassificationConversation(
    genesis: ProjectGenesisViewResponse?,
    isSubmitting: Boolean,
    onAdvance: (ProjectGenesisSubmissionRequest) -> Unit,
) {
    var classification by remember { mutableStateOf("GREENFIELD_LOCAL") }
    var intent by remember { mutableStateOf(genesis?.revision?.productIntent.orEmpty()) }
    Column(Modifier.fillMaxSize()) {
        Text("AUTHORITY CONTEXT", color = GenesisMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(9.dp))
        SegmentedChoice(
            options = listOf(
                "GREENFIELD_LOCAL" to "New local",
                "EXISTING_LOCAL" to "Existing local",
                "ORGANIZATION_GOVERNED" to "Organization",
            ),
            selected = classification,
            onSelect = { classification = it },
        )
        Spacer(Modifier.height(18.dp))
        GuidedField("Product intent", intent, { intent = it }, minLines = 5)
        AnimatedVisibility(classification == "ORGANIZATION_GOVERNED", enter = fadeIn(), exit = fadeOut()) {
            Text(
                "Orchard can record this authority context, but admission remains locked until verified organizational policy sources are available.",
                modifier = Modifier.padding(top = 12.dp),
                color = GenesisAmber,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }
        Spacer(Modifier.weight(1f))
        PrimaryAction("Establish context", isSubmitting, intent.isNotBlank()) {
            onAdvance(
                ProjectGenesisSubmissionRequest(
                    classification = classification,
                    productIntent = intent.trim(),
                    baseRevision = genesis?.revision?.revision ?: 0,
                    baseHash = genesis?.revision?.hash,
                )
            )
        }
    }
}

@Composable
private fun ExperienceConversation(
    genesis: ProjectGenesisViewResponse?,
    isSubmitting: Boolean,
    onAdvance: (ProjectGenesisSubmissionRequest) -> Unit,
) {
    var audience by remember { mutableStateOf("") }
    var promise by remember { mutableStateOf("") }
    var journey by remember { mutableStateOf("") }
    var principles by remember { mutableStateOf("") }
    var qualities by remember { mutableStateOf("") }
    var exclusions by remember { mutableStateOf("") }
    var accessibility by remember { mutableStateOf("") }
    val valid = listOf(audience, promise, journey, principles, qualities, exclusions).all(String::isNotBlank)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        GuidedField("Who is it for?", audience, { audience = it })
        GuidedField("What promise does it make?", promise, { promise = it }, minLines = 2)
        GuidedField("Primary journey, one step per line", journey, { journey = it }, minLines = 3)
        GuidedField("Interaction principles, one per line", principles, { principles = it }, minLines = 3)
        GuidedField("How should it feel?", qualities, { qualities = it }, minLines = 2)
        GuidedField("What must it never feel like?", exclusions, { exclusions = it }, minLines = 2)
        GuidedField("Accessibility commitments", accessibility, { accessibility = it }, minLines = 2)
        Spacer(Modifier.height(18.dp))
        PrimaryAction("Resolve product experience", isSubmitting, valid) {
            onAdvance(
                ProjectGenesisSubmissionRequest(
                    experience = ExperienceContractRequest(
                        audience.trim(),
                        promise.trim(),
                        lines(journey),
                        lines(principles),
                        lines(qualities),
                        lines(exclusions),
                        lines(accessibility),
                    ),
                    baseRevision = requireNotNull(genesis?.revision).revision,
                    baseHash = genesis.revision.hash,
                )
            )
        }
    }
}

@Composable
private fun ArchitectureConversation(
    genesis: ProjectGenesisViewResponse?,
    epics: List<Pair<Int, String>>,
    isSubmitting: Boolean,
    onCreateEpic: (String) -> Unit,
    onAdvance: (ProjectGenesisSubmissionRequest) -> Unit,
) {
    var firstEpicTitle by remember { mutableStateOf("") }
    var componentName by remember { mutableStateOf("") }
    var responsibility by remember { mutableStateOf("") }
    var paths by remember { mutableStateOf("") }
    var adrTitle by remember { mutableStateOf("") }
    var context by remember { mutableStateOf("") }
    var decision by remember { mutableStateOf("") }
    var consequences by remember { mutableStateOf("") }
    var epicId by remember(epics) { mutableStateOf(epics.firstOrNull()?.first ?: 0) }
    val valid = epicId != 0 && listOf(componentName, responsibility, adrTitle, context, decision).all(String::isNotBlank)
    val componentId = slug(componentName)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("FIRST VERTICAL SLICE", color = GenesisMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        if (epics.isEmpty()) {
            GuidedField("Name the first experience-proving epic", firstEpicTitle, { firstEpicTitle = it }, minLines = 2)
            PrimaryAction("Create first epic", isSubmitting, firstEpicTitle.isNotBlank()) {
                onCreateEpic(firstEpicTitle.trim())
            }
        } else {
            SegmentedChoice(epics.map { it.first.toString() to it.second }, epicId.toString()) { epicId = it.toInt() }
        }
        Spacer(Modifier.height(18.dp))
        Text("FOUNDING COMPONENT", color = GenesisMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        GuidedField("Component name", componentName, { componentName = it })
        GuidedField("Responsibility", responsibility, { responsibility = it }, minLines = 2)
        GuidedField("Repository paths, one per line", paths, { paths = it }, minLines = 2)
        Spacer(Modifier.height(12.dp))
        Text("FOUNDING DECISION", color = GenesisMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        GuidedField("ADR title", adrTitle, { adrTitle = it })
        GuidedField("Context", context, { context = it }, minLines = 2)
        GuidedField("Decision", decision, { decision = it }, minLines = 2)
        GuidedField("Consequences, one per line", consequences, { consequences = it }, minLines = 2)
        Spacer(Modifier.height(18.dp))
        PrimaryAction("Form architecture", isSubmitting, valid) {
            onAdvance(
                ProjectGenesisSubmissionRequest(
                    components = listOf(
                        ArchitectureComponentRequest(
                            componentId = componentId,
                            name = componentName.trim(),
                            responsibility = responsibility.trim(),
                            repositoryPaths = lines(paths),
                        )
                    ),
                    decisions = listOf(
                        ArchitectureDecisionRequest(
                            decisionId = "ADR-GENESIS-1",
                            title = adrTitle.trim(),
                            context = context.trim(),
                            decision = decision.trim(),
                            consequences = lines(consequences),
                            componentIds = listOf(componentId),
                        )
                    ),
                    firstEpicId = epicId,
                    baseRevision = requireNotNull(genesis?.revision).revision,
                    baseHash = genesis.revision.hash,
                )
            )
        }
    }
}

@Composable
private fun BlueprintConversation(
    genesis: ProjectGenesisViewResponse?,
    isSubmitting: Boolean,
    onAdvance: (ProjectGenesisSubmissionRequest) -> Unit,
) {
    var rootName by remember { mutableStateOf("") }
    var toolchain by remember { mutableStateOf("") }
    var modules by remember { mutableStateOf("") }
    var commands by remember { mutableStateOf("") }
    var policies by remember { mutableStateOf("") }
    val valid = listOf(rootName, toolchain, modules, commands).all(String::isNotBlank)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        GuidedField("Repository name", rootName, { rootName = it })
        GuidedField("Toolchain", toolchain, { toolchain = it })
        GuidedField("Modules, one per line", modules, { modules = it }, minLines = 3)
        GuidedField("Verification commands, one per line", commands, { commands = it }, minLines = 3)
        GuidedField("Policy pack IDs, one per line", policies, { policies = it }, minLines = 2)
        Spacer(Modifier.height(18.dp))
        PrimaryAction("Compile repository blueprint", isSubmitting, valid) {
            onAdvance(
                ProjectGenesisSubmissionRequest(
                    blueprint = RepositoryBlueprintRequest(
                        rootName.trim(),
                        toolchain.trim(),
                        lines(modules),
                        lines(commands),
                        lines(policies),
                    ),
                    baseRevision = requireNotNull(genesis?.revision).revision,
                    baseHash = genesis.revision.hash,
                )
            )
        }
    }
}

@Composable
private fun AdmissionConversation(
    genesis: ProjectGenesisViewResponse?,
    isSubmitting: Boolean,
    onAdmit: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Text(
            "Admission makes this exact experience, architecture, first epic, and repository blueprint the authority for implementation.",
            color = GenesisInk,
            fontSize = 14.sp,
            lineHeight = 21.sp,
        )
        Text(
            "Revision ${genesis?.revision?.revision ?: 0}  /  ${genesis?.revision?.hash?.take(12).orEmpty()}",
            modifier = Modifier.padding(top = 16.dp),
            color = GenesisMuted,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
        )
        genesis?.blockingReason?.let {
            Text(it, Modifier.padding(top = 14.dp), color = GenesisAmber, fontSize = 12.sp)
        }
        Spacer(Modifier.weight(1f))
        PrimaryAction("Admit product genesis", isSubmitting, genesis?.blockingReason == null, onAdmit)
    }
}

@Composable
private fun ReadyConversation(
    company: CompanyProjectResponse?,
    isSubmitting: Boolean,
    onStartCompany: () -> Unit,
    onPromote: (Long) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.size(52.dp).background(GenesisGreenSoft, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Check, null, tint = GenesisGreen, modifier = Modifier.size(25.dp))
        }
        Text(
            company?.phase?.lowercase()?.replace('_', ' ')?.replaceFirstChar(Char::uppercase)
                ?: "Company circuit is ready",
            Modifier.padding(top = 16.dp),
            color = GenesisInk,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Bold,
            fontSize = 21.sp,
        )
        Text(
            company?.requiredDecision ?: "Orchard can now form the first governed delivery slice and staff it locally.",
            Modifier.padding(top = 7.dp),
            color = GenesisMuted,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
        company?.assignments?.lastOrNull()?.let { assignment ->
            Surface(
                color = GenesisCanvas,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
            ) {
                Column(Modifier.padding(15.dp)) {
                    Text("CURRENT STAFFING", color = GenesisBlue, fontWeight = FontWeight.Bold, fontSize = 9.sp)
                    Text("${assignment.role.lowercase().replace('_', ' ')}  /  ${assignment.model}", Modifier.padding(top = 7.dp), color = GenesisInk, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text(assignment.rationale, Modifier.padding(top = 5.dp), color = GenesisMuted, fontSize = 10.sp, lineHeight = 15.sp)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        val promotableRun = company?.acceptances?.lastOrNull { acceptance ->
            company.promotions.none { it.runId == acceptance.runId }
        }?.runId
        if (promotableRun != null) {
            PrimaryAction("Promote accepted candidate locally", isSubmitting, true) { onPromote(promotableRun) }
        } else if (company == null || company.phase in setOf("PORTFOLIO_PLANNING", "DELIVERY_PLANNING", "STAFFING")) {
            PrimaryAction("Start local company circuit", isSubmitting, true, onStartCompany)
        } else {
            Text(
                "The company circuit is advancing under admitted authority. Refresh to inspect current evidence and decisions.",
                color = GenesisMuted,
                fontSize = 11.sp,
                lineHeight = 17.sp,
            )
        }
    }
}

@Composable
private fun ProjectionSurface(
    modifier: Modifier,
    phase: String,
    genesis: ProjectGenesisViewResponse?,
    company: CompanyProjectResponse?,
    executionPlan: RepositoryExecutionPlanResponse?,
    engineeringStandards: EngineeringStandardsViewResponse?,
    remediationCampaigns: List<RemediationCampaignViewResponse>,
    campaignResolutions: List<CampaignResolutionViewResponse>,
    isSavingStandard: Boolean,
    isScanningConformance: Boolean,
    isAdmittingConformance: Boolean,
    isResolvingCampaign: Boolean,
    onSaveStandard: (EngineeringStandardSubmissionRequest) -> Unit,
    onScanConformance: () -> Unit,
    onAdmitConformance: (Long) -> Unit,
    onProposeCampaignResolution: (Long) -> Unit,
    onAdmitCampaignResolution: (Long) -> Unit,
) {
    Column(modifier.background(GenesisCanvas).padding(32.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("LIVE PRODUCT STATE", color = GenesisBlue, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                Text("What the conversation has resolved", color = GenesisInk, fontFamily = FontFamily.Serif, fontSize = 22.sp, modifier = Modifier.padding(top = 7.dp))
            }
            Icon(Icons.Default.Lock, contentDescription = null, tint = GenesisMuted, modifier = Modifier.size(17.dp))
            Text("Authority projection", color = GenesisMuted, fontSize = 10.sp, modifier = Modifier.padding(start = 7.dp))
        }
        Spacer(Modifier.height(24.dp))
        AnimatedContent(
            targetState = phase,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = { fadeIn(tween(320)) togetherWith fadeOut(tween(180)) },
            label = "projection-phase",
        ) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                if (genesis?.revision == null) EmptyProjection()
                else if (phase == GENESIS_READY && company != null) CompanyProjection(genesis, company, executionPlan)
                else GenesisProjection(genesis)
                engineeringStandards?.let {
                    EngineeringStandardsProjection(
                        standards = it,
                        isSaving = isSavingStandard,
                        isScanning = isScanningConformance,
                        isAdmitting = isAdmittingConformance,
                        onSave = onSaveStandard,
                        onScan = onScanConformance,
                        onAdmit = onAdmitConformance,
                    )
                }
                remediationCampaigns.forEach { campaign ->
                    RemediationCampaignProjection(
                        campaign,
                        campaignResolutions.singleOrNull { it.case.campaignId == campaign.campaign.campaignId },
                        isResolvingCampaign,
                        onProposeCampaignResolution,
                        onAdmitCampaignResolution,
                    )
                }
            }
        }
    }
}

@Composable
private fun RemediationCampaignProjection(
    view: RemediationCampaignViewResponse,
    resolution: CampaignResolutionViewResponse?,
    isResolving: Boolean,
    onPropose: (Long) -> Unit,
    onAdmit: (Long) -> Unit,
) {
    val color = when (view.state) {
        "CLOSED" -> GenesisGreen
        "BLOCKED", "ESCALATED" -> GenesisRed
        "VERIFYING" -> GenesisAmber
        else -> GenesisBlue
    }
    ProjectionSection("REMEDIATION CAMPAIGN ${view.campaign.campaignId}") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(color, CircleShape))
            Text(
                view.state.replace('_', ' '),
                Modifier.padding(start = 8.dp).weight(1f),
                color = color,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
            )
            Text("Standard ${view.campaign.standardRevision}", color = GenesisMuted, fontSize = 9.sp)
        }
        MetadataLine("Seed scan ${view.campaign.seedScanId}", view.campaign.seedRepositoryRevision.take(12))
        view.campaign.successorSource?.let { source ->
            MetadataLine("Successor to campaign ${source.predecessorCampaignId}", "Case ${source.resolutionCaseId}")
        }
        view.campaign.links.forEach { link ->
            Text(link.practiceId, Modifier.padding(top = 8.dp), color = GenesisInk, fontWeight = FontWeight.Bold, fontSize = 10.sp)
            Text(
                "${link.backlogNodeIds.size} work nodes  /  ${link.admittedEntityIds.size} admitted entities",
                color = GenesisMuted,
                fontSize = 9.sp,
            )
        }
        view.evaluations.lastOrNull()?.let { evaluation ->
            Divider(Modifier.padding(vertical = 10.dp), color = GenesisLine)
            MetadataLine("Follow-up scan ${evaluation.scanId}", evaluation.repositoryRevision.take(12))
            evaluation.practices.forEach { practice ->
                val practiceColor = when {
                    practice.regressed -> GenesisRed
                    practice.resolved -> GenesisGreen
                    else -> GenesisAmber
                }
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).background(practiceColor, CircleShape))
                    Text(practice.practiceId, Modifier.padding(start = 7.dp).weight(1f), color = GenesisInk, fontSize = 9.sp)
                    Text(
                        "${practice.priorDisposition.replace('_', ' ')} -> ${practice.currentDisposition.replace('_', ' ')}",
                        color = practiceColor,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 8.sp,
                    )
                }
            }
            Text(
                "Promotion ${evaluation.promotionIds.joinToString()} supplied the repository revision evaluated for closure.",
                Modifier.padding(top = 8.dp),
                color = GenesisMuted,
                fontSize = 9.sp,
                lineHeight = 13.sp,
            )
        } ?: Text(
            "The admitted campaign advances one bounded leaf at a time through definition, coding, verification, independent audit, promotion, and follow-up scan.",
            Modifier.padding(top = 9.dp),
            color = GenesisMuted,
            fontSize = 9.sp,
            lineHeight = 14.sp,
        )
        resolution?.let { caseView ->
            Divider(Modifier.padding(vertical = 10.dp), color = GenesisLine)
            Text("RESOLUTION CASE ${caseView.case.caseId}", color = GenesisRed, fontWeight = FontWeight.Bold, fontSize = 9.sp)
            Text(
                caseView.case.cause.replace('_', ' ').lowercase().replaceFirstChar(Char::uppercase),
                Modifier.padding(top = 5.dp),
                color = GenesisInk,
                fontSize = 10.sp,
            )
            Text(
                caseView.case.practiceIds.joinToString(),
                Modifier.padding(top = 4.dp),
                color = GenesisMuted,
                fontFamily = FontFamily.Monospace,
                fontSize = 8.sp,
            )
            val proposal = caseView.proposals.lastOrNull()
            if (proposal == null) {
                TextButton(onClick = { onPropose(caseView.case.caseId) }, enabled = !isResolving) {
                    Text(if (isResolving) "Architect reasoning..." else "Ask Architect for resolution")
                }
            } else {
                Text(proposal.action.replace('_', ' '), Modifier.padding(top = 8.dp), color = GenesisBlue, fontWeight = FontWeight.Bold, fontSize = 9.sp)
                Text(proposal.rationale, Modifier.padding(top = 4.dp), color = GenesisInk, fontSize = 9.sp, lineHeight = 13.sp)
                Text(proposal.instructions, Modifier.padding(top = 4.dp), color = GenesisMuted, fontSize = 9.sp, lineHeight = 13.sp)
                if (caseView.admission == null) {
                    TextButton(onClick = { onAdmit(proposal.proposalId) }, enabled = !isResolving) {
                        Text(if (isResolving) "Admitting..." else "Admit resolution decision")
                    }
                } else {
                    Text(
                        if (caseView.admission.admittedNodes.isEmpty()) "Decision admitted without repository mutation"
                        else "Decision admitted  /  ${caseView.admission.admittedNodes.size} governed work nodes",
                        Modifier.padding(top = 8.dp),
                        color = GenesisGreen,
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun EngineeringStandardsProjection(
    standards: EngineeringStandardsViewResponse,
    isSaving: Boolean,
    isScanning: Boolean,
    isAdmitting: Boolean,
    onSave: (EngineeringStandardSubmissionRequest) -> Unit,
    onScan: () -> Unit,
    onAdmit: (Long) -> Unit,
) {
    val current = standards.standards.maxByOrNull { it.revision }
    var name by remember(current?.hash) { mutableStateOf(current?.name ?: "Project engineering standard") }
    var practices by remember(current?.hash, standards.baseline) {
        mutableStateOf(current?.practices ?: standards.baseline)
    }
    val latestScan = standards.scans.maxByOrNull { it.scanId }
    val admitted = latestScan?.let { scan -> standards.admissions.any { it.scanId == scan.scanId } } == true

    ProjectionSection("ENGINEERING STANDARDS") {
        MetadataLine(
            if (current == null) "BUILT-IN BASELINE" else "REVISION ${current.revision}",
            "${practices.count { it.enabled }} enabled",
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            enabled = !isSaving,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            singleLine = true,
            label = { Text("Standard name") },
        )
        practices.forEachIndexed { index, practice ->
            PracticeEditor(
                practice,
                !isSaving,
                onChange = { updated -> practices = practices.toMutableList().also { it[index] = updated } },
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                enabled = name.isNotBlank() && practices.any { it.enabled } && !isSaving,
                onClick = { onSave(EngineeringStandardSubmissionRequest(name.trim(), practices)) },
                colors = ButtonDefaults.textButtonColors(contentColor = GenesisGreen),
            ) {
                if (isSaving) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                else Text(if (current == null) "Adopt baseline" else "Save revision")
            }
            TextButton(
                enabled = current != null && !isScanning,
                onClick = onScan,
                colors = ButtonDefaults.textButtonColors(contentColor = GenesisBlue),
            ) {
                if (isScanning) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                else Text("Run full scan")
            }
        }
    }

    latestScan?.let { scan ->
        ProjectionSection("REPOSITORY CONFORMANCE") {
            MetadataLine("SCAN ${scan.scanId}", scan.repositoryRevision.take(12))
            scan.findings.forEach { finding ->
                val color = when (finding.disposition) {
                    "CONFORMING", "NOT_APPLICABLE", "EXCEPTION_ACTIVE" -> GenesisGreen
                    "PARTIAL", "UNKNOWN", "CONFLICTING" -> GenesisAmber
                    else -> GenesisRed
                }
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).background(color, CircleShape))
                    Text(finding.practiceId, Modifier.padding(start = 8.dp).weight(1f), color = GenesisInk, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                    Text(finding.disposition.replace('_', ' '), color = color, fontWeight = FontWeight.Bold, fontSize = 9.sp)
                }
                Text(finding.summary, Modifier.padding(top = 4.dp), color = GenesisMuted, fontSize = 10.sp, lineHeight = 15.sp)
                finding.citations.forEach { citation ->
                    Text(
                        "${citation.path}  ${citation.contentHash.take(10)}",
                        Modifier.padding(top = 4.dp),
                        color = GenesisBlue,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                    )
                    Text(citation.observation, color = GenesisMuted, fontSize = 9.sp, lineHeight = 13.sp)
                }
            }
            if (scan.proposedBacklog.isNotEmpty()) {
                Text("CANDIDATE BACKLOG", Modifier.padding(top = 14.dp), color = GenesisBlue, fontWeight = FontWeight.Bold, fontSize = 9.sp)
                scan.proposedBacklog.forEach { node ->
                    val indent = when (node.type) { "STORY" -> 10.dp; "EPIC" -> 0.dp; else -> 20.dp }
                    Text(
                        "${node.type}  ${node.title}",
                        Modifier.padding(start = indent, top = 6.dp),
                        color = GenesisInk,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                    )
                }
                TextButton(
                    enabled = !admitted && !isAdmitting,
                    onClick = { onAdmit(scan.scanId) },
                    colors = ButtonDefaults.textButtonColors(contentColor = GenesisGreen),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    if (isAdmitting) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    else Text(if (admitted) "Backlog admitted" else "Admit candidate backlog")
                }
            }
        }
    }
}

@Composable
private fun PracticeEditor(
    practice: EngineeringPracticeResponse,
    enabled: Boolean,
    onChange: (EngineeringPracticeResponse) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.Top) {
        Checkbox(
            checked = practice.enabled,
            onCheckedChange = { onChange(practice.copy(enabled = it)) },
            enabled = enabled,
        )
        Column(Modifier.weight(1f).padding(top = 3.dp)) {
            Text(practice.title, color = GenesisInk, fontWeight = FontWeight.Bold, fontSize = 10.sp)
            Text(practice.requirement, color = GenesisMuted, fontSize = 9.sp, lineHeight = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            TextButton(
                enabled = enabled && practice.enabled,
                onClick = {
                    onChange(practice.copy(severity = if (practice.severity == "REQUIRED") "ADVISORY" else "REQUIRED"))
                },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            ) {
                Text(practice.severity, color = if (practice.severity == "REQUIRED") GenesisRed else GenesisMuted, fontSize = 9.sp)
            }
        }
    }
}

@Composable
private fun CompanyProjection(
    genesis: ProjectGenesisViewResponse,
    company: CompanyProjectResponse,
    executionPlan: RepositoryExecutionPlanResponse?,
) {
    ProjectionSection("COMPANY HEALTH") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(9.dp).background(
                    when (company.health) {
                        "AT_RISK" -> GenesisRed
                        "DECISION_REQUIRED" -> GenesisAmber
                        else -> GenesisGreen
                    },
                    CircleShape,
                )
            )
            Text(company.health.replace('_', ' '), Modifier.padding(start = 8.dp), color = GenesisInk, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(company.phase.replace('_', ' '), Modifier.weight(1f), color = GenesisMuted, fontSize = 10.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
        company.requiredDecision?.let { Text(it, Modifier.padding(top = 10.dp), color = GenesisAmber, fontSize = 11.sp, lineHeight = 17.sp) }
    }
    ProjectionSection("ACTIVE CIRCUIT") {
        MetadataLine("Rules ${company.ruleSet?.rules?.size ?: 0}", "Assignments ${company.assignments.size}")
        MetadataLine("Audits ${company.audits.size}", "Promotions ${company.promotions.size}")
        company.assignments.takeLast(3).forEach { assignment ->
            Text(assignment.role.replace('_', ' '), Modifier.padding(top = 10.dp), color = GenesisInk, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            Text("${assignment.model}  /  ${assignment.risk} risk  /  ${(assignment.confidence * 100).toInt()}% evidence confidence", color = GenesisMuted, fontSize = 9.sp)
        }
    }
    executionPlan?.let { plan ->
        ProjectionSection("ANALYSIS & EXECUTION PLAN") {
            MetadataLine(plan.content.disposition.replace('_', ' '), "Plan ${plan.revision}")
            Text(plan.content.summary, Modifier.padding(top = 8.dp), color = GenesisInk, fontSize = 10.sp, lineHeight = 15.sp)
            Text("Broad analysis 88K  /  bounded coding 24K", Modifier.padding(top = 8.dp), color = GenesisBlue, fontWeight = FontWeight.Bold, fontSize = 9.sp)
            if (plan.content.reuse.isNotEmpty()) {
                Text("Reuse  ${plan.content.reuse.joinToString()}", Modifier.padding(top = 8.dp), color = GenesisMuted, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
            }
            plan.content.operations.forEach { operation ->
                Text("${operation.order}. ${operation.action} ${operation.path}", Modifier.padding(top = 8.dp), color = GenesisInk, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                Text(operation.instruction, color = GenesisMuted, fontSize = 9.sp, lineHeight = 14.sp)
            }
        }
    }
    company.ruleSet?.let { rules ->
        ProjectionSection("ARCHITECTURE & COMPLIANCE") {
            rules.rules.take(8).forEach { rule ->
                Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.padding(top = 4.dp).size(7.dp).background(GenesisBlue, CircleShape))
                    Column(Modifier.padding(start = 9.dp)) {
                        Text(rule.ruleId, color = GenesisInk, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                        Text(rule.statement, color = GenesisMuted, fontSize = 10.sp, lineHeight = 15.sp)
                    }
                }
            }
        }
    }
    if (company.audits.isNotEmpty()) {
        ProjectionSection("INDEPENDENT AUDIT") {
            company.audits.takeLast(4).forEach { audit ->
                val color = if (audit.status == "CONFORMING") GenesisGreen else GenesisRed
                Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).background(color, CircleShape))
                    Text(audit.role.replace('_', ' '), Modifier.padding(start = 8.dp).weight(1f), color = GenesisInk, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                    Text(audit.status, color = color, fontWeight = FontWeight.Bold, fontSize = 9.sp)
                }
                Text(audit.rationale, color = GenesisMuted, fontSize = 10.sp, lineHeight = 15.sp)
            }
        }
    }
    ProjectionSection("ACCOUNTABILITY") {
        company.accountability.takeLast(10).forEach { link ->
            Text("${link.from}  ${link.relation.lowercase().replace('_', ' ')}  ${link.to}", Modifier.padding(vertical = 3.dp), color = GenesisMuted, fontFamily = FontFamily.Monospace, fontSize = 9.sp, maxLines = 2)
        }
    }
    ProjectionSection("ADMITTED PRODUCT") {
        ProjectionText(requireNotNull(genesis.revision).experience.productPromise)
        MetadataLine("Genesis ${genesis.revision.revision}", genesis.revision.hash.take(12))
    }
}

@Composable
private fun EmptyProjection() {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(44.dp).background(Color(0xFFE7EAE8), CircleShape))
        Text("The product will take shape here", Modifier.padding(top = 15.dp), color = GenesisInk, fontFamily = FontFamily.Serif, fontSize = 18.sp)
        Text("Each resolved decision becomes durable, correlated state.", Modifier.padding(top = 6.dp), color = GenesisMuted, fontSize = 11.sp)
    }
}

@Composable
private fun GenesisProjection(genesis: ProjectGenesisViewResponse) {
    val revision = requireNotNull(genesis.revision)
    ProjectionSection("PRODUCT INTENT") {
        ProjectionText(revision.productIntent)
        MetadataLine(revision.classification.orEmpty(), "Revision ${revision.revision}")
    }
    if (revision.experience.productPromise.isNotBlank()) {
        ProjectionSection("EXPERIENCE CONTRACT") {
            Text(revision.experience.productPromise, color = GenesisInk, fontFamily = FontFamily.Serif, fontSize = 18.sp, lineHeight = 24.sp)
            MetadataLine("For ${revision.experience.audience}", revision.experience.emotionalQualities.joinToString("  /  "))
            ProjectionList("Primary journey", revision.experience.primaryJourney)
            ProjectionList("Interaction principles", revision.experience.interactionPrinciples)
            ProjectionList("Must not feel like", revision.experience.mustNotFeelLike, GenesisRed)
        }
    }
    if (revision.components.isNotEmpty()) {
        ProjectionSection("ARCHITECTURE") {
            revision.components.forEach { component ->
                Text(component.name, color = GenesisInk, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(component.responsibility, color = GenesisMuted, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
                MetadataLine(component.componentId, component.repositoryPaths.joinToString())
            }
        }
    }
    if (revision.decisions.isNotEmpty()) {
        ProjectionSection("CORRELATED DECISIONS") {
            revision.decisions.forEach { decision ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).background(if (decision.status == "ADMITTED") GenesisGreen else GenesisAmber, CircleShape))
                    Text(decision.title, Modifier.padding(start = 8.dp).weight(1f), color = GenesisInk, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(decision.status, color = if (decision.status == "ADMITTED") GenesisGreen else GenesisAmber, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
                Text(decision.decision, color = GenesisMuted, fontSize = 11.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 7.dp, bottom = 12.dp))
            }
        }
    }
    revision.blueprint?.let { blueprint ->
        ProjectionSection("REPOSITORY BLUEPRINT") {
            Text(blueprint.rootName, color = GenesisInk, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            MetadataLine(blueprint.toolchain, blueprint.modules.joinToString("  /  "))
            ProjectionList("Verification", blueprint.verificationCommands)
        }
    }
    if (revision.admitted) {
        Surface(color = GenesisGreenSoft, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Check, null, tint = GenesisGreen, modifier = Modifier.size(18.dp))
                Text("Admitted implementation authority", Modifier.padding(start = 10.dp), color = GenesisGreen, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
    }
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun ProjectionSection(label: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 22.dp)) {
        Text(label, color = GenesisBlue, fontWeight = FontWeight.Bold, fontSize = 9.sp)
        Spacer(Modifier.height(9.dp))
        content()
        Divider(Modifier.padding(top = 18.dp), color = GenesisLine)
    }
}

@Composable
private fun ProjectionText(text: String) {
    Text(text, color = GenesisInk, fontSize = 13.sp, lineHeight = 20.sp)
}

@Composable
private fun MetadataLine(left: String, right: String) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(left, color = GenesisMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(right, color = GenesisMuted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ProjectionList(label: String, values: List<String>, color: Color = GenesisInk) {
    if (values.isEmpty()) return
    Text(label.uppercase(), color = GenesisMuted, fontWeight = FontWeight.Bold, fontSize = 8.sp, modifier = Modifier.padding(top = 13.dp, bottom = 4.dp))
    values.forEachIndexed { index, value ->
        Row(Modifier.padding(vertical = 2.dp)) {
            Text("${index + 1}.", color = GenesisMuted, fontSize = 10.sp, modifier = Modifier.width(22.dp))
            Text(value, color = color, fontSize = 11.sp, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun GuidedField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    minLines: Int = 1,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 11.sp) },
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
        minLines = minLines,
        maxLines = (minLines + 3).coerceAtLeast(4),
        shape = RoundedCornerShape(8.dp),
        colors = androidx.compose.material.TextFieldDefaults.outlinedTextFieldColors(
            focusedBorderColor = GenesisGreen,
            unfocusedBorderColor = GenesisLine,
            focusedLabelColor = GenesisGreen,
            cursorColor = GenesisGreen,
            backgroundColor = Color.White,
            textColor = GenesisInk,
        ),
        textStyle = MaterialTheme.typography.body2.copy(fontSize = 12.sp, lineHeight = 18.sp),
    )
}

@Composable
private fun SegmentedChoice(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    if (options.isEmpty()) {
        Text("Create the first epic conversationally before architecture can be formed.", color = GenesisAmber, fontSize = 11.sp)
        return
    }
    Row(Modifier.fillMaxWidth().background(Color(0xFFE8EBE9), RoundedCornerShape(8.dp)).padding(3.dp)) {
        options.forEach { (value, label) ->
            val active = value == selected
            Box(
                Modifier.weight(1f).heightIn(min = 36.dp)
                    .background(if (active) Color.White else Color.Transparent, RoundedCornerShape(6.dp))
                    .clickable { onSelect(value) }
                    .padding(horizontal = 8.dp, vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (active) GenesisInk else GenesisMuted, fontSize = 10.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal, maxLines = 2)
            }
        }
    }
}

@Composable
private fun PrimaryAction(
    label: String,
    loading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = Modifier.fillMaxWidth().height(46.dp),
        colors = ButtonDefaults.textButtonColors(
            backgroundColor = if (enabled) GenesisInk else GenesisLine,
            contentColor = Color.White,
            disabledContentColor = Color.White.copy(alpha = 0.7f),
        ),
        shape = RoundedCornerShape(8.dp),
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
        } else {
            Text(label, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.padding(start = 8.dp).size(15.dp))
        }
    }
}

private fun lines(value: String): List<String> = value.lines().map(String::trim).filter(String::isNotEmpty).distinct()

private fun slug(value: String): String = value.trim().lowercase().map { character ->
    if (character.isLetterOrDigit()) character else '-'
}.joinToString("").replace(Regex("-+"), "-").trim('-').ifBlank { "component" }
