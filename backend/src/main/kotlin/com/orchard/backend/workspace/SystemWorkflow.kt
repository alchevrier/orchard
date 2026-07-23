package com.orchard.backend.workspace

import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi

const val DEFINITION_NEEDS_CLARIFICATION = "NEEDS_CLARIFICATION"
const val DEFINITION_NEEDS_INVESTIGATION = "NEEDS_INVESTIGATION"
const val DEFINITION_NEEDS_SPLIT = "NEEDS_SPLIT"
const val DEFINITION_READY = "READY"
const val REPOSITORY_EVIDENCE_ALL_MATCHES = "ALL_MATCHES"
const val REPOSITORY_EVIDENCE_AFFINE_TEST = "AFFINE_TEST"
const val REPOSITORY_EVIDENCE_MATCH_ANY = "ANY"
const val REPOSITORY_EVIDENCE_MATCH_ALL = "ALL"

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class SystemWorkflow(
    val id: String,
    val version: Int,
    val workItemType: Int,
    val phases: List<String>,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val stepDefinitions: List<WorkflowStepDefinition> = emptyList(),
)

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class WorkDefinitionSubmission(
    val requestedOutcome: String,
    val currentBehavior: String,
    val requiredBehavior: String,
    val scope: List<String>,
    val nonGoals: List<String>,
    val constraints: List<String>,
    val acceptanceCriteria: List<AcceptanceCriterion>,
    val unresolvedQuestions: List<String> = emptyList(),
    val proposedSplitTitles: List<String> = emptyList(),
    val reproduction: String = "",
    val regressionCriterion: String = "",
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val repositoryEvidenceSelectors: List<RepositoryEvidenceSelector> = emptyList(),
)

@Serializable
data class RepositoryEvidenceSelector(
    val selectorId: String,
    val scopeIndexes: List<Int>,
    val pathGlobs: List<String>,
    val contentLiterals: List<String> = emptyList(),
    val contentMatch: String = REPOSITORY_EVIDENCE_MATCH_ANY,
    val selection: String = REPOSITORY_EVIDENCE_ALL_MATCHES,
    val affinitySelectorId: String = "",
)

@Serializable
data class AcceptanceCriterion(
    val description: String,
    val verification: String,
)

@Serializable
data class DefinitionAssessment(
    val status: String,
    val missingFields: List<String>,
    val ambiguities: List<String> = emptyList(),
)

object DefaultSystemWorkflow {
    fun resolve(workItemType: Int): SystemWorkflow {
        require(workItemType == ENTITY_TASK || workItemType == ENTITY_BUG) {
            "Only tasks and bugs have work-definition workflows"
        }
        val step = definitionStep(workItemType)
        return SystemWorkflow(
            id = "default-definition-${if (workItemType == ENTITY_BUG) "bug" else "task"}",
            version = 2,
            workItemType = workItemType,
            phases = listOf(step.id),
            stepDefinitions = listOf(step),
        )
    }

    fun assess(workItemType: Int, submission: WorkDefinitionSubmission): DefinitionAssessment {
        resolve(workItemType)
        val step = resolve(workItemType).stepDefinitions.single()
        if (submission.proposedSplitTitles.any { it.isNotBlank() }) {
            return assessment(step, DEFINITION_NEEDS_SPLIT)
        }
        val missing = buildList {
            if (submission.requestedOutcome.isBlank()) add("requestedOutcome")
            if (submission.currentBehavior.isBlank()) add("currentBehavior")
            if (submission.requiredBehavior.isBlank()) add("requiredBehavior")
            if (submission.scope.none { it.isNotBlank() }) add("scope")
            if (submission.nonGoals.none { it.isNotBlank() }) add("nonGoals")
            if (submission.acceptanceCriteria.isEmpty()) add("acceptanceCriteria")
            if (submission.acceptanceCriteria.any { it.description.isBlank() || it.verification.isBlank() }) {
                add("acceptanceCriteria.verification")
            }
            if (workItemType == ENTITY_BUG && submission.reproduction.isBlank()) add("reproduction")
            if (workItemType == ENTITY_BUG && submission.regressionCriterion.isBlank()) add("regressionCriterion")
        }
        if (missing.isNotEmpty()) return assessment(step, DEFINITION_NEEDS_INVESTIGATION, missing)
        val ambiguities = submission.unresolvedQuestions.filter { it.isNotBlank() }
        if (ambiguities.isNotEmpty()) {
            return assessment(step, DEFINITION_NEEDS_CLARIFICATION, ambiguities = ambiguities)
        }
        return assessment(step, DEFINITION_READY)
    }

    fun isCompatible(workflow: SystemWorkflow): Boolean =
        workflow == resolve(workflow.workItemType) || workflow == legacy(workflow.workItemType)

    private fun definitionStep(workItemType: Int): WorkflowStepDefinition = WorkflowStepDefinition(
        id = if (workItemType == ENTITY_BUG) "DEFINE_BUG" else "DEFINE_TASK",
        version = 1,
        startCondition = WorkflowStartCondition(listOf(FACT_WORK_ITEM_EXISTS)),
        contextContract = WorkflowContextContract(listOf(CONTEXT_TICKET, CONTEXT_SYSTEM_POLICY)),
        executionContract = WorkflowExecutionContract(listOf(EXECUTOR_HUMAN, EXECUTOR_AGENT)),
        evidenceContract = EvidenceContract(
            id = "${if (workItemType == ENTITY_BUG) "bug" else "task"}-definition",
            version = 1,
            requirements = listOf(
                EvidenceRequirement("WORK_DEFINITION", "A structured and assessed work definition manifest."),
            ),
        ),
        transitionSignals = listOf(
            WorkflowTransitionSignal(DEFINITION_NEEDS_INVESTIGATION, DEFINITION_NEEDS_INVESTIGATION, false),
            WorkflowTransitionSignal(DEFINITION_NEEDS_CLARIFICATION, DEFINITION_NEEDS_CLARIFICATION, false),
            WorkflowTransitionSignal(DEFINITION_NEEDS_SPLIT, DEFINITION_NEEDS_SPLIT, false),
            WorkflowTransitionSignal(DEFINITION_READY, DEFINITION_READY, true),
        ),
        interactionContract = WorkflowInteractionContract(
            actorAuthorities = listOf(
                WorkflowActorAuthority(COLLABORATOR_HUMAN, listOf(ACTION_PROPOSE, ACTION_REVISE, ACTION_FEEDBACK, ACTION_ACCEPT)),
                WorkflowActorAuthority(COLLABORATOR_LOCAL_LLM, listOf(ACTION_PROPOSE, ACTION_REVISE)),
                WorkflowActorAuthority(ACTOR_DETERMINISTIC_POLICY, listOf(ACTION_ASSESS)),
            )
        ),
        modelExecutionProfileId = "bounded-definition-reasoning-v1",
    )

    private fun legacy(workItemType: Int): SystemWorkflow {
        require(workItemType == ENTITY_TASK || workItemType == ENTITY_BUG)
        return SystemWorkflow(
            id = "default-definition-${if (workItemType == ENTITY_BUG) "bug" else "task"}",
            version = 1,
            workItemType = workItemType,
            phases = if (workItemType == ENTITY_BUG) {
                listOf(
                    "CAPTURE_REPORT",
                    "COLLECT_DIAGNOSTICS",
                    "ESTABLISH_REPRODUCTION",
                    "DEFINE_EXPECTED_BEHAVIOR",
                    "LOCALIZE_SCOPE",
                    "DEFINE_REGRESSION_CRITERION",
                    "DERIVE_EVIDENCE_CONTRACT",
                    "ASSESS_AMBIGUITY",
                )
            } else {
                listOf(
                    "CAPTURE_INTENT",
                    "INSPECT_CONTEXT",
                    "DEFINE_OUTCOME",
                    "DEFINE_SCOPE",
                    "DEFINE_ACCEPTANCE_CRITERIA",
                    "DEFINE_NON_GOALS",
                    "DERIVE_EVIDENCE_CONTRACT",
                    "ASSESS_AMBIGUITY",
                )
            },
        )
    }

    private fun assessment(
        step: WorkflowStepDefinition,
        outcome: String,
        missingFields: List<String> = emptyList(),
        ambiguities: List<String> = emptyList(),
    ): DefinitionAssessment {
        val signal = WorkflowStepEngine.resolveSignal(step, outcome)
        return DefinitionAssessment(signal.target, missingFields, ambiguities)
    }
}