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
import com.orchard.frontend.network.ExperienceContractRequest
import com.orchard.frontend.network.GenesisProposalResponse
import com.orchard.frontend.network.ProjectGenesisSubmissionRequest
import com.orchard.frontend.network.ProjectGenesisViewResponse
import com.orchard.frontend.network.RepositoryObjectiveAssessmentResponse

private val SetupSurface = OrchardDesktopColors.surface
private val SetupRaised = OrchardDesktopColors.raised
private val SetupInk = OrchardDesktopColors.ink
private val SetupMuted = OrchardDesktopColors.muted
private val SetupLine = OrchardDesktopColors.line
private val SetupGreen = OrchardDesktopColors.green
private val SetupGreenSoft = OrchardDesktopColors.greenSoft
private val SetupBlue = OrchardDesktopColors.blue
private val SetupAmber = OrchardDesktopColors.amber
private val SetupAmberSoft = OrchardDesktopColors.amberSoft

internal data class ConductorProjectSetupState(
    val projectId: Int,
    val projectTitle: String,
    val genesis: ProjectGenesisViewResponse,
    val standards: EngineeringStandardsViewResponse,
    val epics: List<Pair<Int, String>>,
)

internal data class ReadyAction(val label: String, val prompt: String)
internal data class DeliveryGuidance(val message: String, val actionRequired: Boolean = false)

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
        "BLUEPRINT" -> ConductorSetupStep.ADMISSION
        "ADMISSION" -> ConductorSetupStep.ADMISSION
        else -> ConductorSetupStep.READY
    }
}

@Composable
internal fun ConductorProjectSetupCard(
    state: ConductorProjectSetupState,
    proposal: GenesisProposalResponse?,
    repositoryAssessment: RepositoryObjectiveAssessmentResponse?,
    proposalFeedback: String?,
    isSubmitting: Boolean,
    error: String?,
    onAdoptStandards: (EngineeringStandardSubmissionRequest) -> Unit,
    onAdvance: (ProjectGenesisSubmissionRequest) -> Unit,
    onGenerateProposal: (String) -> Unit,
    onApplyProposal: () -> Unit,
    onAdmit: () -> Unit,
    onCreateFirstOutcome: (String) -> Unit,
    onSuggestedAction: (String) -> Unit,
    deliveryGuidance: DeliveryGuidance?,
    onStartDelivery: () -> Unit,
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
                    ConductorSetupStep.BLUEPRINT -> ProposalStep(
                        state,
                        step,
                        proposal,
                        repositoryAssessment,
                        isSubmitting,
                        proposalFeedback,
                        onAdvance,
                        onGenerateProposal,
                        onApplyProposal,
                        onCreateFirstOutcome,
                    )
                    ConductorSetupStep.ARCHITECTURE -> OutcomeStep(
                        state,
                        repositoryAssessment,
                        isSubmitting,
                        onCreateFirstOutcome,
                    )
                    ConductorSetupStep.ADMISSION -> AdmissionStep(state, isSubmitting, onAdmit)
                    ConductorSetupStep.READY -> ReadyStep(
                        state,
                        isSubmitting,
                        deliveryGuidance,
                        onStartDelivery,
                        onSuggestedAction,
                    )
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
        ConductorSetupStep.ARCHITECTURE to "Outcome",
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
private fun OutcomeStep(
    state: ConductorProjectSetupState,
    repositoryAssessment: RepositoryObjectiveAssessmentResponse?,
    isSubmitting: Boolean,
    onRecordOutcome: (String) -> Unit,
) {
    val recordedOutcome = state.epics.firstOrNull()?.second.orEmpty()
    var outcome by remember(state.projectId, recordedOutcome) { mutableStateOf(recordedOutcome) }
    Text("Set the intended outcome", color = SetupInk, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    Text(
        "Name the first result Orchard should deliver. Architecture, repository shape, sizing, and verification will be established through governed design when the work needs them.",
        Modifier.padding(top = 7.dp),
        color = SetupMuted,
        fontSize = 12.sp,
        lineHeight = 18.sp,
    )
    OutlinedTextField(
        value = outcome,
        onValueChange = { outcome = it },
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
        enabled = !isSubmitting && recordedOutcome.isEmpty(),
        minLines = 2,
        maxLines = 5,
        label = { Text("Intended outcome") },
    )
    repositoryAssessment?.let { RepositoryAssessmentSummary(it) }
    SetupAction(
        label = if (recordedOutcome.isEmpty()) "Record outcome and review" else "Continue to review",
        loadingLabel = "Recording outcome...",
        isSubmitting = isSubmitting,
        enabled = outcome.isNotBlank(),
        onClick = { onRecordOutcome(outcome.trim()) },
    )
}

@Composable
private fun ProposalStep(
    state: ConductorProjectSetupState,
    step: ConductorSetupStep,
    proposal: GenesisProposalResponse?,
    repositoryAssessment: RepositoryObjectiveAssessmentResponse?,
    isSubmitting: Boolean,
    feedback: String?,
    onAdvance: (ProjectGenesisSubmissionRequest) -> Unit,
    onGenerate: (String) -> Unit,
    onApply: () -> Unit,
    onCreateFirstOutcome: (String) -> Unit,
) {
    val firstOutcome = state.epics.firstOrNull()?.second.orEmpty()
    var prompt by remember(state.genesis.phase, state.genesis.revision?.hash, firstOutcome, repositoryAssessment?.assessmentId) {
        mutableStateOf(
            repositoryAssessment?.objective?.let(::proposalObjectiveForContinuation) ?: proposalPromptDefault(
                step,
                state.genesis.revision?.productIntent.orEmpty(),
                state.genesis.nextQuestion,
                firstOutcome,
            )
        )
    }
    var epicTitle by remember(state.projectId, state.epics) { mutableStateOf("") }
    var showManualExperience by remember(state.genesis.revision?.hash) { mutableStateOf(false) }
    val candidate = proposal?.takeIf { it.phase == state.genesis.phase }
    val unresolvedQuestions = candidateBlockingQuestions(candidate?.unresolvedQuestions)
    var refinementAnswers by remember(state.genesis.revision?.hash, unresolvedQuestions) {
        mutableStateOf(unresolvedQuestions.associateWith { "" })
    }
    val needsRefinement = proposalNeedsRefinement(unresolvedQuestions, feedback)
    val heading = proposalStepHeading(step, candidate != null, firstOutcome.isNotEmpty(), needsRefinement)
    Text(heading, color = SetupInk, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    Text(
        when {
            needsRefinement && candidate != null ->
                "The Architect recorded optional questions. Answer them to refine the draft, or continue with the provisional proposal."
            needsRefinement -> "Add the missing details below, then ask the Architect to refine the proposal."
            candidate != null -> "The candidate is ready for review. Apply it to record this decision and continue to the next step."
            step == ConductorSetupStep.ARCHITECTURE && firstOutcome.isNotEmpty() ->
                "The first working outcome is recorded. Now form the technical plan needed to deliver it."
            else -> state.genesis.nextQuestion
        },
        Modifier.padding(top = 7.dp),
        color = SetupMuted,
        fontSize = 12.sp,
        lineHeight = 18.sp,
    )

    if (step == ConductorSetupStep.EXPERIENCE) {
        TextButton(
            onClick = { showManualExperience = !showManualExperience },
            enabled = !isSubmitting,
            modifier = Modifier.padding(top = 4.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = SetupBlue),
        ) {
            Text(if (showManualExperience) "Use Architect instead" else "Enter experience details manually")
        }
        if (showManualExperience) {
            ManualExperienceStep(state, isSubmitting, onAdvance)
            return
        }
    }

    if (step == ConductorSetupStep.ARCHITECTURE && state.epics.isEmpty()) {
        Text(
            "Start with one concrete outcome people should be able to complete. Orchard will record it, form the technical plan, and present that plan for your review.",
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
            label = { Text("First working outcome") },
        )
        SetupAction(
            label = "Create outcome and form plan",
            loadingLabel = "Creating outcome and forming plan...",
            isSubmitting = isSubmitting,
            enabled = epicTitle.isNotBlank(),
            onClick = { onCreateFirstOutcome(epicTitle.trim()) },
        )
        return
    }

    if (step == ConductorSetupStep.ARCHITECTURE) {
        Column(
            Modifier.fillMaxWidth().padding(top = 12.dp).background(SetupGreenSoft, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Text("OUTCOME RECORDED", color = SetupGreen, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text(firstOutcome, Modifier.padding(top = 4.dp), color = SetupInk, fontSize = 11.sp, lineHeight = 16.sp)
        }
    }
    OutlinedTextField(
        value = prompt,
        onValueChange = { prompt = it },
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
        enabled = !isSubmitting,
        minLines = 3,
        maxLines = 8,
        label = { Text(if (needsRefinement) "Original description" else proposalPromptLabel(step)) },
    )
    Text(
        "The Architect forms a draft from this description. Nothing is recorded until you apply it.",
        Modifier.padding(top = 8.dp),
        color = SetupMuted,
        fontSize = 10.sp,
        lineHeight = 15.sp,
    )
    val canGenerate = prompt.isNotBlank() && unresolvedQuestions.all {
        refinementAnswers[it].orEmpty().isNotBlank()
    }
    if (needsRefinement) {
        Column(
            Modifier.fillMaxWidth().padding(top = 12.dp).background(SetupAmberSoft, RoundedCornerShape(6.dp)).padding(10.dp),
        ) {
            Text(
                if (candidate != null) "OPTIONAL REFINEMENT" else "DETAILS NEEDED",
                color = SetupAmber,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
            )
            if (candidate != null) {
                Text(
                    "These questions are preserved for later design work and do not block moving forward.",
                    Modifier.padding(top = 5.dp),
                    color = SetupInk,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                )
            }
            if (!feedback.isNullOrBlank()) {
                Text(feedback, Modifier.padding(top = 5.dp), color = SetupInk, fontSize = 11.sp, lineHeight = 16.sp)
            }
            unresolvedQuestions.forEach { question ->
                Text(
                    refinementQuestionPrompt(question),
                    Modifier.padding(top = 10.dp),
                    color = SetupInk,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Medium,
                )
                if (!question.trim().endsWith("?")) {
                    Text(
                        question.trim(),
                        Modifier.padding(top = 3.dp),
                        color = SetupMuted,
                        fontSize = 10.sp,
                        lineHeight = 15.sp,
                    )
                }
                OutlinedTextField(
                    value = refinementAnswers[question].orEmpty(),
                    onValueChange = { answer -> refinementAnswers = refinementAnswers + (question to answer) },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    enabled = !isSubmitting,
                    minLines = 2,
                    maxLines = 5,
                    label = { Text("Answer") },
                )
            }
            SetupAction(
                label = repositoryAssessmentActionLabel(
                    hasProposal = candidate != null,
                    hasAssessment = repositoryAssessment != null,
                    needsRefinement = true,
                ),
                loadingLabel = "Submitting answers and validating proposal...",
                isSubmitting = isSubmitting,
                enabled = canGenerate,
                onClick = { onGenerate(proposalRefinementPrompt(prompt, unresolvedQuestions, refinementAnswers)) },
            )
            when {
                isSubmitting -> Text(
                    "Answers submitted. The Architect is correlating them with repository evidence.",
                    Modifier.padding(top = 6.dp),
                    color = SetupBlue,
                    fontSize = 10.sp,
                    lineHeight = 15.sp,
                )
                !feedback.isNullOrBlank() -> Text(
                    feedback,
                    Modifier.fillMaxWidth().padding(top = 6.dp).background(SetupSurface, RoundedCornerShape(6.dp)).padding(8.dp),
                    color = SetupAmber,
                    fontSize = 10.sp,
                    lineHeight = 15.sp,
                )
                candidate != null -> Text(
                    "Proposal ready below. Review it before applying.",
                    Modifier.padding(top = 6.dp),
                    color = SetupGreen,
                    fontSize = 10.sp,
                    lineHeight = 15.sp,
                )
                !canGenerate -> Text(
                    "Complete every answer field to submit this refinement.",
                    Modifier.padding(top = 6.dp),
                    color = SetupMuted,
                    fontSize = 10.sp,
                    lineHeight = 15.sp,
                )
            }
        }
    } else {
        if (candidate != null || repositoryAssessment == null) {
            SetupAction(
                label = repositoryAssessmentActionLabel(
                    hasProposal = candidate != null,
                    hasAssessment = repositoryAssessment != null,
                    needsRefinement = false,
                ),
                loadingLabel = "Forming and validating proposal...",
                isSubmitting = isSubmitting,
                enabled = canGenerate,
                onClick = { onGenerate(proposalRefinementPrompt(prompt, unresolvedQuestions, refinementAnswers)) },
            )
        }
    }
    if (candidate == null && repositoryAssessment != null) {
        RepositoryAssessmentSummary(repositoryAssessment)
        SetupAction(
            label = repositoryAssessmentActionLabel(
                hasProposal = false,
                hasAssessment = true,
                needsRefinement = false,
            ),
            loadingLabel = "Forming provisional proposal...",
            isSubmitting = isSubmitting,
            enabled = canGenerate,
            onClick = { onGenerate(proposalRefinementPrompt(prompt, unresolvedQuestions, refinementAnswers)) },
        )
    }
    candidate?.let {
        Column(Modifier.fillMaxWidth().padding(top = 14.dp).background(SetupRaised, RoundedCornerShape(6.dp)).padding(12.dp)) {
            Text(if (needsRefinement) "PROVISIONAL CANDIDATE" else "CANDIDATE", color = SetupBlue, fontWeight = FontWeight.Bold, fontSize = 9.sp)
            it.repositoryRevision?.let { revision ->
                Text(
                    "GROUNDED IN CODE  ${revision.take(12)}",
                    Modifier.padding(top = 9.dp),
                    color = SetupGreen,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp,
                )
                it.repositoryEvidence.forEach { evidence ->
                    Text(
                        "${evidence.path}  ${evidence.contentHash.take(8)}",
                        Modifier.padding(top = 4.dp),
                        color = SetupMuted,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                    )
                }
                if (it.omittedRepositoryFileCount > 0) {
                    Text(
                        "${it.omittedRepositoryFileCount} additional tracked files were outside this bounded context.",
                        Modifier.padding(top = 4.dp),
                        color = SetupMuted,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                    )
                }
                it.repositoryAssessment?.claims.orEmpty().forEach { claim ->
                    Text(
                        claim.status.replace('_', ' '),
                        Modifier.padding(top = 8.dp),
                        color = when (claim.status) {
                            "SUPPORTED" -> SetupGreen
                            "CONTRADICTED" -> SetupAmber
                            else -> SetupBlue
                        },
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp,
                    )
                    Text(
                        claim.statement,
                        Modifier.padding(top = 3.dp),
                        color = SetupInk,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                    )
                    (claim.support + claim.defeaters).distinctBy { evidence -> evidence.path }.forEach { evidence ->
                        Text(
                            "${evidence.path}  ${evidence.contentHash.take(8)}",
                            Modifier.padding(top = 3.dp),
                            color = SetupMuted,
                            fontSize = 10.sp,
                            lineHeight = 14.sp,
                        )
                    }
                }
                if (it.repositoryAssessment?.claims.orEmpty().any { claim -> claim.status == "UNESTABLISHED" }) {
                    Text(
                        "Unestablished items are not blockers. Apply the provisional proposal and Orchard will correlate them more deeply during design and delivery.",
                        Modifier.padding(top = 9.dp),
                        color = SetupBlue,
                        fontSize = 10.sp,
                        lineHeight = 15.sp,
                    )
                }
            }
            proposalLines(it).forEach { line ->
                Text(line, Modifier.padding(top = 6.dp), color = SetupInk, fontSize = 11.sp, lineHeight = 16.sp)
            }
            SetupAction(
                label = proposalApplyActionLabel(needsRefinement),
                loadingLabel = "Applying proposal...",
                isSubmitting = isSubmitting,
                enabled = true,
                onClick = onApply,
            )
        }
    }
}

internal fun proposalApplyActionLabel(hasOptionalQuestions: Boolean): String =
    if (hasOptionalQuestions) "Continue with provisional proposal" else "Apply proposal"

internal fun repositoryAssessmentActionLabel(
    hasProposal: Boolean,
    hasAssessment: Boolean,
    needsRefinement: Boolean,
): String = if (needsRefinement) {
    "Submit answers and refine proposal"
} else if (!hasProposal && hasAssessment) {
    "Next: form proposal"
} else {
    proposalActionLabel(hasProposal, needsRefinement)
}

@Composable
private fun RepositoryAssessmentSummary(assessment: RepositoryObjectiveAssessmentResponse) {
    Column(Modifier.fillMaxWidth().padding(top = 14.dp).background(SetupRaised, RoundedCornerShape(6.dp)).padding(12.dp)) {
        Text(
            "FAST REPOSITORY SCAN  ${assessment.repositoryRevision.take(12)}",
            color = SetupGreen,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp,
        )
        Text(
            "This is a fast, provisional view of the current repository, not a complete implementation map. Orchard will refine and correlate it more deeply during design and delivery.",
            Modifier.padding(top = 5.dp),
            color = SetupMuted,
            fontSize = 10.sp,
            lineHeight = 15.sp,
        )
        assessment.claims.forEach { claim ->
            Text(
                claim.status.replace('_', ' '),
                Modifier.padding(top = 8.dp),
                color = when (claim.status) {
                    "SUPPORTED" -> SetupGreen
                    "CONTRADICTED" -> SetupAmber
                    else -> SetupBlue
                },
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp,
            )
            Text(claim.statement, Modifier.padding(top = 3.dp), color = SetupInk, fontSize = 11.sp, lineHeight = 16.sp)
        }
        if (assessment.unresolvedQuestions.any(String::isNotBlank)) {
            Text(
                "CORRELATE LATER",
                Modifier.padding(top = 10.dp),
                color = SetupBlue,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp,
            )
            Text(
                "The bounded scan could not establish every implementation detail. Continue with a provisional proposal; Orchard will correlate these gaps more deeply during design and delivery.",
                Modifier.padding(top = 3.dp),
                color = SetupInk,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }
    }
}

internal fun candidateBlockingQuestions(candidateQuestions: List<String>?): List<String> =
    candidateQuestions.orEmpty().filter(String::isNotBlank)

internal fun proposalObjectiveForContinuation(value: String): String = value
    .replace(LEGACY_EVIDENCE_QUESTION_DIRECTIVE, " ")
    .replace(Regex("\\s+"), " ")
    .trim()

internal fun proposalNeedsRefinement(unresolvedQuestions: List<String>, feedback: String?): Boolean =
    unresolvedQuestions.any(String::isNotBlank) || !feedback.isNullOrBlank()

private val LEGACY_EVIDENCE_QUESTION_DIRECTIVE = Regex(
    "Ask explicit questions wherever the implementation cannot be established from repository evidence\\.?",
    RegexOption.IGNORE_CASE,
)

internal fun refinementQuestionPrompt(unresolved: String): String = unresolved.trim().let { value ->
    if (value.endsWith("?")) value else "How should Orchard resolve this evidence gap?"
}

internal fun proposalRefinementPrompt(
    originalPrompt: String,
    unresolvedQuestions: List<String>,
    answers: Map<String, String>,
): String = buildString {
    append(originalPrompt.trim())
    val answered = unresolvedQuestions.mapNotNull { question ->
        answers[question]?.trim()?.takeIf(String::isNotEmpty)?.let { answer -> question to answer }
    }
    if (answered.isNotEmpty()) {
        append("\n\nAnswers to the Architect's questions:")
        answered.forEach { (question, answer) ->
            append("\nQuestion: ").append(question.trim())
            append("\nAnswer: ").append(answer)
        }
    }
}

internal fun proposalActionLabel(hasProposal: Boolean, needsRefinement: Boolean): String = when {
    needsRefinement -> "Refine proposal"
    hasProposal -> "Regenerate proposal"
    else -> "Form proposal"
}

internal fun proposalStepHeading(
    step: ConductorSetupStep,
    hasProposal: Boolean,
    hasFirstOutcome: Boolean = false,
    needsRefinement: Boolean = false,
): String = when (step) {
    ConductorSetupStep.EXPERIENCE -> when {
        needsRefinement -> "Complete the experience proposal"
        hasProposal -> "Review the experience proposal"
        else -> "Describe the experience"
    }
    ConductorSetupStep.ARCHITECTURE -> when {
        needsRefinement -> "Complete the architecture proposal"
        hasProposal -> "Review the architecture proposal"
        hasFirstOutcome -> "Design how to deliver the first outcome"
        else -> "Plan the first working outcome"
    }
    ConductorSetupStep.BLUEPRINT -> when {
        needsRefinement -> "Complete the repository proposal"
        hasProposal -> "Review the repository proposal"
        else -> "Confirm the repository plan"
    }
    else -> error("Proposal heading requested for $step")
}

internal fun proposalPromptDefault(
    step: ConductorSetupStep,
    productIntent: String,
    nextQuestion: String,
    firstOutcome: String = "",
): String = when (step) {
    ConductorSetupStep.EXPERIENCE -> productIntent.trim().ifEmpty { nextQuestion.trim() }
    ConductorSetupStep.ARCHITECTURE -> firstOutcome.trim().ifEmpty { nextQuestion.trim() }
    ConductorSetupStep.BLUEPRINT -> nextQuestion.trim()
    else -> ""
}

@Composable
private fun ManualExperienceStep(
    state: ConductorProjectSetupState,
    isSubmitting: Boolean,
    onAdvance: (ProjectGenesisSubmissionRequest) -> Unit,
) {
    var audience by remember(state.genesis.revision?.hash) { mutableStateOf("") }
    var promise by remember(state.genesis.revision?.hash) { mutableStateOf("") }
    var journey by remember(state.genesis.revision?.hash) { mutableStateOf("") }
    var principles by remember(state.genesis.revision?.hash) { mutableStateOf("") }
    var qualities by remember(state.genesis.revision?.hash) { mutableStateOf("") }
    var exclusions by remember(state.genesis.revision?.hash) { mutableStateOf("") }
    var accessibility by remember(state.genesis.revision?.hash) { mutableStateOf("") }
    val required = listOf(audience, promise, journey, principles, qualities, exclusions)
    ManualExperienceField("Who is it for?", audience, { audience = it }, isSubmitting)
    ManualExperienceField("What promise does it make?", promise, { promise = it }, isSubmitting)
    ManualExperienceField("Primary journey, one step per line", journey, { journey = it }, isSubmitting)
    ManualExperienceField("Interaction principles, one per line", principles, { principles = it }, isSubmitting)
    ManualExperienceField("How should it feel?", qualities, { qualities = it }, isSubmitting)
    ManualExperienceField("What must it never feel like?", exclusions, { exclusions = it }, isSubmitting)
    ManualExperienceField("Accessibility commitments", accessibility, { accessibility = it }, isSubmitting)
    SetupAction(
        label = "Apply experience",
        loadingLabel = "Recording experience...",
        isSubmitting = isSubmitting,
        enabled = required.all(String::isNotBlank),
        onClick = {
            onAdvance(ProjectGenesisSubmissionRequest(
                experience = ExperienceContractRequest(
                    audience = audience.trim(),
                    productPromise = promise.trim(),
                    primaryJourney = setupLines(journey),
                    interactionPrinciples = setupLines(principles),
                    emotionalQualities = setupLines(qualities),
                    mustNotFeelLike = setupLines(exclusions),
                    accessibility = setupLines(accessibility),
                ),
                baseRevision = requireNotNull(state.genesis.revision).revision,
                baseHash = state.genesis.revision.hash,
            ))
        },
    )
}

@Composable
private fun ManualExperienceField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    isSubmitting: Boolean,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().padding(top = 9.dp),
        enabled = !isSubmitting,
        minLines = 2,
        maxLines = 5,
        label = { Text(label) },
    )
}

private fun setupLines(value: String): List<String> =
    value.lines().map(String::trim).filter(String::isNotEmpty).distinct()

private fun proposalPromptLabel(step: ConductorSetupStep): String = when (step) {
    ConductorSetupStep.EXPERIENCE -> "Who is this for, what should they accomplish, and how should it feel?"
    ConductorSetupStep.ARCHITECTURE -> "What technical approach and decisions will deliver this outcome?"
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
        "This admits the intended experience and first outcome as project authority. Architecture, repository shape, and verification remain deferred to governed design and delivery.",
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
private fun ReadyStep(
    state: ConductorProjectSetupState,
    isSubmitting: Boolean,
    deliveryGuidance: DeliveryGuidance?,
    onStartDelivery: () -> Unit,
    onSuggestedAction: (String) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp).background(SetupGreenSoft, RoundedCornerShape(17.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Check, null, Modifier.size(18.dp), tint = SetupGreen)
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text("${state.projectTitle} is ready", color = SetupInk, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(
                "The intended experience and first outcome are now project authority. Orchard can begin governed delivery, help shape another outcome, or report what is currently active.",
                color = SetupMuted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
        }
    }
    Text(
        "What would you like Orchard to do next?",
        Modifier.padding(top = 14.dp),
        color = SetupInk,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
    )
    if (deliveryGuidance == null) {
        SetupAction(
            label = "Start delivery",
            loadingLabel = "Starting governed delivery...",
            isSubmitting = isSubmitting,
            enabled = true,
            onClick = onStartDelivery,
        )
    } else {
        Text(
            if (deliveryGuidance.actionRequired) "Your input is needed" else "Orchard is working",
            Modifier.padding(top = 12.dp),
            color = if (deliveryGuidance.actionRequired) SetupAmber else SetupGreen,
            fontWeight = FontWeight.SemiBold,
            fontSize = 10.sp,
        )
        Text(
            deliveryGuidance.message,
            Modifier.fillMaxWidth().padding(top = 5.dp)
                .background(if (deliveryGuidance.actionRequired) SetupAmberSoft else SetupGreenSoft, RoundedCornerShape(6.dp))
                .padding(10.dp),
            color = if (deliveryGuidance.actionRequired) SetupAmber else SetupGreen,
            fontSize = 11.sp,
            lineHeight = 16.sp,
        )
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        readyActions(state).forEach { action ->
            TextButton(onClick = { onSuggestedAction(action.prompt) }) {
                Text(action.label)
            }
        }
    }
    Text(
        "You can also ask in your own words about a feature, repository evidence, project status, or the next outcome.",
        Modifier.padding(top = 4.dp),
        color = SetupMuted,
        fontSize = 10.sp,
        lineHeight = 15.sp,
    )
}

internal fun readyActions(state: ConductorProjectSetupState): List<ReadyAction> = listOf(
    ReadyAction(
        "Plan another outcome",
        "Help me define the next outcome for ${state.projectTitle}.",
    ),
    ReadyAction(
        "Show status",
        "Report current workspace status for project ${state.projectId}.",
    ),
)

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