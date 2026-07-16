package com.orchard.backend.workspace

import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class WorkflowStepDefinition(
    val id: String,
    val version: Int,
    val startCondition: WorkflowStartCondition,
    val contextContract: WorkflowContextContract,
    val executionContract: WorkflowExecutionContract,
    val evidenceContract: EvidenceContract,
    val transitionSignals: List<WorkflowTransitionSignal>,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val interactionContract: WorkflowInteractionContract? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val modelExecutionProfileId: String? = null,
)

@Serializable
data class WorkflowInteractionContract(
    val actorAuthorities: List<WorkflowActorAuthority>,
)

@Serializable
data class WorkflowActorAuthority(
    val actor: String,
    val allowedActions: List<String>,
)

@Serializable
data class WorkflowStartCondition(
    val requiredFacts: List<String>,
)

@Serializable
data class WorkflowContextContract(
    val requiredContext: List<String>,
)

@Serializable
data class WorkflowExecutionContract(
    val allowedExecutors: List<String>,
)

@Serializable
data class WorkflowTransitionSignal(
    val signal: String,
    val target: String,
    val accepted: Boolean,
)

object WorkflowStepEngine {
    fun canStart(step: WorkflowStepDefinition, availableFacts: Set<String>): Boolean =
        step.startCondition.requiredFacts.all { it in availableFacts }

    fun hasRequiredContext(step: WorkflowStepDefinition, availableContext: Set<String>): Boolean =
        step.contextContract.requiredContext.all { it in availableContext }

    fun resolveSignal(step: WorkflowStepDefinition, signal: String): WorkflowTransitionSignal =
        step.transitionSignals.singleOrNull { it.signal == signal }
            ?: throw IllegalArgumentException("Step ${step.id} does not declare signal $signal")

    fun canPerform(step: WorkflowStepDefinition, actor: String, action: String): Boolean =
        step.interactionContract?.actorAuthorities
            ?.singleOrNull { it.actor == actor }
            ?.allowedActions
            ?.contains(action) == true
}

const val SIGNAL_EVIDENCE_ACCEPTED = "EVIDENCE_ACCEPTED"
const val SIGNAL_EVIDENCE_REJECTED = "EVIDENCE_REJECTED"
const val SIGNAL_COMPLETED = "COMPLETED"
const val SIGNAL_CANCELLED = "CANCELLED"

const val FACT_WORK_ITEM_EXISTS = "WORK_ITEM_EXISTS"
const val FACT_WORK_DEFINITION_READY = "WORK_DEFINITION_READY"
const val FACT_REPOSITORY_AVAILABLE = "REPOSITORY_AVAILABLE"
const val FACT_REPOSITORY_CLEAN = "REPOSITORY_CLEAN"

const val CONTEXT_TICKET = "TICKET"
const val CONTEXT_SYSTEM_POLICY = "SYSTEM_POLICY"
const val CONTEXT_WORK_DEFINITION = "WORK_DEFINITION"
const val CONTEXT_REPOSITORY_REVISION = "REPOSITORY_REVISION"
const val CONTEXT_EPISODIC_MEMORY = "EPISODIC_MEMORY"

const val EXECUTOR_HUMAN = "HUMAN"
const val EXECUTOR_AGENT = "AGENT"
const val EXECUTOR_DETERMINISTIC_TOOL = "DETERMINISTIC_TOOL"

const val ACTOR_DETERMINISTIC_POLICY = "DETERMINISTIC_POLICY"
const val ACTION_PROPOSE = "PROPOSE"
const val ACTION_REVISE = "REVISE"
const val ACTION_FEEDBACK = "FEEDBACK"
const val ACTION_ACCEPT = "ACCEPT"
const val ACTION_ASSESS = "ASSESS"