package com.orchard.frontend.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Checkbox
import androidx.compose.material.CheckboxDefaults
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orchard.frontend.network.EngineeringPracticeResponse
import com.orchard.frontend.network.EngineeringStandardSubmissionRequest
import com.orchard.frontend.network.EngineeringStandardsViewResponse
import com.orchard.frontend.network.GenesisProposalResponse
import com.orchard.frontend.network.ProjectGenesisSubmissionRequest
import com.orchard.frontend.network.ProjectGenesisViewResponse

private val SetupSurface = Color(0xFFFFFFFF)
private val SetupRaised = Color(0xFFFAFAFC)
private val SetupInk = Color(0xFF1D1D1F)
private val SetupMuted = Color(0xFF6E6E73)
private val SetupLine = Color(0xFFE5E5EA)
private val SetupGreen = Color(0xFF277A57)
private val SetupGreenSoft = Color(0xFFEAF4EF)
private val SetupBlue = Color(0xFF2877C7)
private val SetupAmber = Color(0xFF936516)
private val SetupAmberSoft = Color(0xFFFAF2E3)

internal data class ConductorProjectSetupState(
    val projectId: Int,
    val projectTitle: String,
    val genesis: ProjectGenesisViewResponse,
    val standards: EngineeringStandardsViewResponse,
    val epics: List<Pair<Int, String>>,
)

internal enum class ConductorSetupStep {
    STANDARDS,
    CLASSIFICATION,
    EXPERIENCE,
    ARCHITECTURE,
    BLUEPRINT,
    ADMISSION,
    READY,
}

internal fun conductorSetupStep(state: ConductorProjectSetupState): ConductorSetupStep {
    if (state.standards.standards.isEmpty()) return ConductorSetupStep.STANDARDS
    return when (state.genesis.phase) {
        "CLASSIFICATION" -> ConductorSetupStep.CLASSIFICATION
        "EXPERIENCE" -> ConductorSetupStep.EXPERIENCE
        "ARCHITECTURE" -> ConductorSetupStep.ARCHITECTURE
        "BLUEPRINT" -> ConductorSetupStep.BLUEPRINT
        "ADMISSION" -> ConductorSetupStep.ADMISSION
        else -> ConductorSetupStep.READY
    }
}

@Composable
internal fun ConductorProjectSetupCard(
    state: ConductorProjectSetupState,
    proposal: GenesisProposalResponse?,
    isSubmitting: Boolean,
    error: String?,
    onAdoptStandards: (EngineeringStandardSubmissionRequest) -> Unit,
    onAdvance: (ProjectGenesisSubmissionRequest) -> Unit,
    onGenerateProposal: (String) -> Unit,
    onApplyProposal: () -> Unit,
    onAdmit: () -> Unit,
    onProposeEpic: (String) -> Unit,
) {
    val step = conductorSetupStep(state)
    Column(Modifier.fillMaxWidth().widthIn(max = 720.dp)) {
        Text("Orchard", color = SetupGreen, fontWeight = FontWeight.SemiBold, fontSize = 10.sp)
        Surface(
            modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
            color = SetupSurface,
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, SetupLine),
        ) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
                SetupProgress(step)
                Spacer(Modifier.height(14.dp))
                when (step) {
                    ConductorSetupStep.STANDARDS -> StandardsStep(state, isSubmitting, onAdoptStandards)
                    ConductorSetupStep.CLASSIFICATION -> ClassificationStep(state, isSubmitting, onAdvance)
                    ConductorSetupStep.EXPERIENCE,
                    ConductorSetupStep.ARCHITECTURE,
                    ConductorSetupStep.BLUEPRINT -> ProposalStep(
                        state,
                        step,
                        proposal,
                        isSubmitting,
                        onGenerateProposal,
                        onApplyProposal,
                        onProposeEpic,
                    )
                    ConductorSetupStep.ADMISSION -> AdmissionStep(state, isSubmitting, onAdmit)
                    ConductorSetupStep.READY -> ReadyStep(state)
                }
                if (!error.isNullOrBlank()) {
                    Text(
                        error,
                        Modifier.fillMaxWidth().padding(top = 12.dp).background(SetupAmberSoft, RoundedCornerShape(6.dp)).padding(10.dp),
                        color = SetupAmber,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun SetupProgress(step: ConductorSetupStep) {
    val steps = listOf(
        ConductorSetupStep.STANDARDS to "Standards",
        ConductorSetupStep.CLASSIFICATION to "Context",
        ConductorSetupStep.EXPERIENCE to "Experience",
        ConductorSetupStep.ARCHITECTURE to "Architecture",
        ConductorSetupStep.BLUEPRINT to "Repository",
        ConductorSetupStep.ADMISSION to "Review",
    )
    val currentIndex = steps.indexOfFirst { it.first == step }.let { if (it == -1) steps.size else it }
    Column {
        Text(
            if (step == ConductorSetupStep.READY) "PROJECT READY" else "PROJECT SETUP  ${currentIndex + 1} OF ${steps.size}",
            color = SetupBlue,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp,
        )
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(steps.size) { index ->
                Box(
                    Modifier.weight(1f).height(3.dp).background(
                        if (index <= currentIndex) SetupGreen else SetupLine,
                        RoundedCornerShape(2.dp),
                    )
                )
            }
        }
    }
}

@Composable
private fun StandardsStep(
    state: ConductorProjectSetupState,
    isSubmitting: Boolean,
    onAdopt: (EngineeringStandardSubmissionRequest) -> Unit,
) {
    var name by remember(state.projectId) { mutableStateOf("${state.projectTitle} engineering standard") }
    var practices by remember(state.projectId, state.standards.baseline) {
        mutableStateOf(state.standards.baseline)
    }
    Text("Let's establish the project's engineering standards", color = SetupInk, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    Text(
        "I couldn't find a project-specific engineering standard in Orchard's authority for this repository. The built-in baseline is below; choose what should govern the work.",
        Modifier.padding(top = 7.dp),
        color = SetupMuted,
        fontSize = 12.sp,
        lineHeight = 18.sp,
    )
    OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
        enabled = !isSubmitting,
        singleLine = true,
        label = { Text("Standard name") },
    )
    practices.forEachIndexed { index, practice ->
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Checkbox(
                checked = practice.enabled,
                onCheckedChange = { enabled ->
                    practices = practices.toMutableList().also { it[index] = practice.copy(enabled = enabled) }
                },
                enabled = !isSubmitting,
                colors = CheckboxDefaults.colors(checkedColor = SetupGreen),
            )
            Column(Modifier.weight(1f).padding(top = 10.dp)) {
                Text(practice.title, color = SetupInk, fontWeight = FontWeight.Medium, fontSize = 12.sp)
                Text(practice.requirement, Modifier.padding(top = 3.dp), color = SetupMuted, fontSize = 10.sp, lineHeight = 15.sp)
            }
        }
    }
    SetupAction(
        label = "Adopt standards",
        loadingLabel = "Recording standards...",
        isSubmitting = isSubmitting,
        enabled = name.isNotBlank() && practices.any { it.enabled },
        onClick = { onAdopt(EngineeringStandardSubmissionRequest(name.trim(), practices)) },
    )
}

@Composable
private fun ClassificationStep(
    state: ConductorProjectSetupState,
    isSubmitting: Boolean,
    onAdvance: (ProjectGenesisSubmissionRequest) -> Unit,
) {
    var classification by remember(state.projectId) { mutableStateOf("EXISTING_LOCAL") }
    var intent by remember(state.genesis.revision?.hash) { mutableStateOf(state.genesis.revision?.productIntent.orEmpty()) }
    Text("What are we building toward?", color = SetupInk, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    Text(
        "The repository is connected. Now establish the context that should guide every later decision.",
        Modifier.padding(top = 7.dp),
        color = SetupMuted,
        fontSize = 12.sp,
        lineHeight = 18.sp,
    )
    Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SetupChoice("EXISTING_LOCAL", "Existing product", classification, !isSubmitting) { classification = it }
        SetupChoice("GREENFIELD_LOCAL", "New product", classification, !isSubmitting) { classification = it }
        SetupChoice("ORGANIZATION_GOVERNED", "Organization", classification, !isSubmitting) { classification = it }
    }
    OutlinedTextField(
        value = intent,
        onValueChange = { intent = it },
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
        enabled = !isSubmitting,
        minLines = 3,
        maxLines = 6,
        label = { Text("What outcome should this project create?") },
    )
    SetupAction(
        label = "Establish project context",
        loadingLabel = "Recording context...",
        isSubmitting = isSubmitting,
        enabled = intent.isNotBlank(),
        onClick = {
            onAdvance(ProjectGenesisSubmissionRequest(
                classification = classification,
                productIntent = intent.trim(),
                baseRevision = state.genesis.revision?.revision ?: 0,
                baseHash = state.genesis.revision?.hash,
            ))
        },
    )
}

@Composable
private fun RowScope.SetupChoice(
    value: String,
    label: String,
    selected: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    val active = value == selected
    Surface(
        modifier = Modifier.weight(1f),
        color = if (active) SetupGreenSoft else SetupRaised,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, if (active) SetupGreen else SetupLine),
    ) {
        TextButton(onClick = { onSelect(value) }, enabled = enabled) {
            Text(label, color = if (active) SetupGreen else SetupMuted, fontSize = 10.sp, maxLines = 1)
        }
    }
}

@Composable
private fun ProposalStep(
    state: ConductorProjectSetupState,
    step: ConductorSetupStep,
    proposal: GenesisProposalResponse?,
    isSubmitting: Boolean,
    onGenerate: (String) -> Unit,
    onApply: () -> Unit,
    onProposeEpic: (String) -> Unit,
) {
    var prompt by remember(state.genesis.phase, state.genesis.revision?.hash) { mutableStateOf("") }
    var epicTitle by remember(state.projectId, state.epics) { mutableStateOf("") }
    val heading = when (step) {
        ConductorSetupStep.EXPERIENCE -> "Describe the experience"
        ConductorSetupStep.ARCHITECTURE -> "Shape the first vertical slice"
        else -> "Confirm the repository plan"
    }
    Text(heading, color = SetupInk, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    Text(state.genesis.nextQuestion, Modifier.padding(top = 7.dp), color = SetupMuted, fontSize = 12.sp, lineHeight = 18.sp)

    if (step == ConductorSetupStep.ARCHITECTURE && state.epics.isEmpty()) {
        Text(
            "Architecture needs one experience-proving epic. Name it here; Orchard will propose it in this conversation for your admission.",
            Modifier.padding(top = 12.dp),
            color = SetupMuted,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
        OutlinedTextField(
            value = epicTitle,
            onValueChange = { epicTitle = it },
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            enabled = !isSubmitting,
            minLines = 2,
            label = { Text("First experience-proving epic") },
        )
        SetupAction(
            label = "Propose first epic",
            loadingLabel = "Forming proposal...",
            isSubmitting = isSubmitting,
            enabled = epicTitle.isNotBlank(),
            onClick = { onProposeEpic(epicTitle.trim()) },
        )
        return
    }

    if (step == ConductorSetupStep.ARCHITECTURE) {
        Text(
            "First epic: ${state.epics.first().second}",
            Modifier.padding(top = 12.dp).background(SetupGreenSoft, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 7.dp),
            color = SetupGreen,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
    }
    OutlinedTextField(
        value = prompt,
        onValueChange = { prompt = it },
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
        enabled = !isSubmitting,
        minLines = 3,
        maxLines = 8,
        label = { Text(proposalPromptLabel(step)) },
    )
    SetupAction(
        label = if (proposal == null) "Form proposal" else "Regenerate proposal",
        loadingLabel = "Architect is reasoning...",
        isSubmitting = isSubmitting,
        enabled = prompt.isNotBlank(),
        onClick = { onGenerate(prompt.trim()) },
    )
    proposal?.takeIf { it.phase == state.genesis.phase }?.let { candidate ->
        Column(Modifier.fillMaxWidth().padding(top = 14.dp).background(SetupRaised, RoundedCornerShape(6.dp)).padding(12.dp)) {
            Text("CANDIDATE", color = SetupBlue, fontWeight = FontWeight.Bold, fontSize = 9.sp)
            proposalLines(candidate).forEach { line ->
                Text(line, Modifier.padding(top = 6.dp), color = SetupInk, fontSize = 11.sp, lineHeight = 16.sp)
            }
            candidate.unresolvedQuestions.forEach { question ->
                Text("Unresolved: $question", Modifier.padding(top = 6.dp), color = SetupAmber, fontSize = 10.sp, lineHeight = 15.sp)
            }
            SetupAction(
                label = "Apply proposal",
                loadingLabel = "Applying proposal...",
                isSubmitting = isSubmitting,
                enabled = true,
                onClick = onApply,
            )
        }
    }
}

private fun proposalPromptLabel(step: ConductorSetupStep): String = when (step) {
    ConductorSetupStep.EXPERIENCE -> "Who is this for, what should they accomplish, and how should it feel?"
    ConductorSetupStep.ARCHITECTURE -> "Describe the slice, constraints, and important technical decisions"
    else -> "Describe the modules, toolchain, and verification commands this repository should use"
}

private fun proposalLines(proposal: GenesisProposalResponse): List<String> {
    val submission = proposal.submission
    submission.experience?.let { experience ->
        return listOf(
            "Audience: ${experience.audience}",
            "Promise: ${experience.productPromise}",
            "Journey: ${experience.primaryJourney.joinToString(" -> ")}",
            "Qualities: ${experience.emotionalQualities.joinToString()}",
        )
    }
    if (submission.components != null || submission.decisions != null) {
        return listOf(
            "Components: ${submission.components.orEmpty().joinToString { it.name }}",
            "Decisions: ${submission.decisions.orEmpty().joinToString { it.title }}",
            "First epic: ${submission.firstEpicId ?: "unresolved"}",
        )
    }
    submission.blueprint?.let { blueprint ->
        return listOf(
            "Repository: ${blueprint.rootName}",
            "Toolchain: ${blueprint.toolchain}",
            "Modules: ${blueprint.modules.joinToString()}",
            "Verification: ${blueprint.verificationCommands.joinToString()}",
        )
    }
    return listOf("The Architect returned a phase-compatible structured candidate.")
}

@Composable
private fun AdmissionStep(
    state: ConductorProjectSetupState,
    isSubmitting: Boolean,
    onAdmit: () -> Unit,
) {
    val revision = state.genesis.revision
    Text("Review the project foundation", color = SetupInk, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    Text(
        "This admits the exact experience, architecture, first epic, and repository plan as implementation authority.",
        Modifier.padding(top = 7.dp),
        color = SetupMuted,
        fontSize = 12.sp,
        lineHeight = 18.sp,
    )
    Text(
        "Revision ${revision?.revision ?: 0}  /  ${revision?.hash?.take(12).orEmpty()}",
        Modifier.padding(top = 12.dp),
        color = SetupMuted,
        fontSize = 10.sp,
    )
    state.genesis.blockingReason?.let { reason ->
        Text(reason, Modifier.padding(top = 10.dp), color = SetupAmber, fontSize = 11.sp, lineHeight = 16.sp)
    }
    SetupAction(
        label = "Admit project foundation",
        loadingLabel = "Admitting foundation...",
        isSubmitting = isSubmitting,
        enabled = state.genesis.blockingReason == null,
        onClick = onAdmit,
    )
}

@Composable
private fun ReadyStep(state: ConductorProjectSetupState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp).background(SetupGreenSoft, RoundedCornerShape(17.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Check, null, Modifier.size(18.dp), tint = SetupGreen)
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text("${state.projectTitle} is ready", color = SetupInk, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text("The project foundation is admitted. Continue the conversation with the outcome you want Orchard to deliver.", color = SetupMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun SetupAction(
    label: String,
    loadingLabel: String,
    isSubmitting: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled && !isSubmitting,
        modifier = Modifier.padding(top = 12.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = SetupGreen),
    ) {
        if (isSubmitting) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = SetupGreen)
            Spacer(Modifier.width(7.dp))
            Text(loadingLabel)
        } else {
            Text(label)
        }
    }
}