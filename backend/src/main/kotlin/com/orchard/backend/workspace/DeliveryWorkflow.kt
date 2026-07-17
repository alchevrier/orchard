package com.orchard.backend.workspace

const val DEFAULT_DELIVERY_WORKFLOW_ID = 1L

const val WORKFLOW_ACCEPTED = 1
const val WORKFLOW_PARENT_REQUIRED = 2
const val WORKFLOW_PARENT_NOT_FOUND = 3
const val WORKFLOW_HIERARCHY_MISMATCH = 4
const val WORKFLOW_UNSUPPORTED_ENTITY = 5

object DefaultDeliveryWorkflow {
    fun requiredParentType(entityType: Int): Int = when (entityType) {
        ENTITY_EPIC -> ENTITY_PROJECT
        ENTITY_STORY -> ENTITY_EPIC
        ENTITY_TASK, ENTITY_BUG -> ENTITY_STORY
        else -> 0
    }

    fun resolve(
        workItemType: Int,
        workDefinition: WorkDefinitionManifest? = null,
        acceptanceContract: AcceptanceContract? = null,
    ): ResolvedWorkflow {
        require(workItemType == ENTITY_TASK || workItemType == ENTITY_BUG) { "Only tasks and bugs can start a workflow" }
        val requirements = buildList {
            add(EvidenceRequirement("SOURCE_DIFF", "A source diff tied to the pinned repository revision."))
            if (workItemType == ENTITY_BUG) {
                add(EvidenceRequirement("REGRESSION_TEST", "A regression test that demonstrates the corrected defect."))
            }
            add(EvidenceRequirement("BUILD", "A successful build against the resulting revision."))
            add(EvidenceRequirement("TEST", "A successful relevant test suite against the resulting revision."))
            workDefinition?.let { manifest ->
                add(
                    EvidenceRequirement(
                        "ACCEPTANCE",
                        manifest.definition.acceptanceCriteria.joinToString(" ") {
                            "${it.description} Verification: ${it.verification}"
                        },
                    )
                )
            }
            acceptanceContract?.criteria?.forEach { criterion ->
                add(
                    EvidenceRequirement(
                        kind = criterionEvidenceKind(criterion.criterionId),
                        description = criterion.description,
                        criterionId = criterion.criterionId,
                        requirementId = criterion.requirementId,
                        gate = criterion.gate,
                        verification = criterion.verification,
                    )
                )
            }
        }
        val evidenceContract = EvidenceContract(
            id = "${if (workItemType == ENTITY_BUG) "bug" else "task"}-completion",
            version = when {
                acceptanceContract != null -> 3
                workDefinition != null -> 2
                else -> 1
            },
            requirements = requirements,
        )
        val step = WorkflowStepDefinition(
            id = "DELIVER_CHANGE",
            version = 1,
            startCondition = WorkflowStartCondition(
                listOf(FACT_WORK_DEFINITION_READY, FACT_REPOSITORY_AVAILABLE, FACT_REPOSITORY_CLEAN)
            ),
            contextContract = WorkflowContextContract(
                listOf(
                    CONTEXT_TICKET,
                    CONTEXT_SYSTEM_POLICY,
                    CONTEXT_WORK_DEFINITION,
                    CONTEXT_REPOSITORY_REVISION,
                    CONTEXT_EPISODIC_MEMORY,
                )
            ),
            executionContract = WorkflowExecutionContract(
                listOf(EXECUTOR_HUMAN, EXECUTOR_AGENT, EXECUTOR_DETERMINISTIC_TOOL)
            ),
            evidenceContract = evidenceContract,
            transitionSignals = listOf(
                WorkflowTransitionSignal(SIGNAL_EVIDENCE_ACCEPTED, RUN_STATE_EVIDENCE_PENDING, true),
                WorkflowTransitionSignal(SIGNAL_EVIDENCE_REJECTED, RUN_STATE_EVIDENCE_BLOCKED, false),
                WorkflowTransitionSignal(SIGNAL_COMPLETED, RUN_STATE_DONE, true),
                WorkflowTransitionSignal(SIGNAL_CANCELLED, RUN_STATE_CANCELLED, true),
            ),
            interactionContract = WorkflowInteractionContract(
                actorAuthorities = listOf(
                    WorkflowActorAuthority(EXECUTOR_HUMAN, listOf("EXECUTE", "PRODUCE_EVIDENCE", "CANCEL")),
                    WorkflowActorAuthority(EXECUTOR_AGENT, listOf("EXECUTE", "PRODUCE_EVIDENCE")),
                    WorkflowActorAuthority(EXECUTOR_DETERMINISTIC_TOOL, listOf("PRODUCE_EVIDENCE")),
                    WorkflowActorAuthority(ACTOR_DETERMINISTIC_POLICY, listOf(ACTION_ASSESS)),
                )
            ),
        )
        return ResolvedWorkflow(
            id = "default-delivery-${if (workItemType == ENTITY_BUG) "bug" else "task"}",
            version = evidenceContract.version,
            workItemType = workItemType,
            steps = listOf(step.id),
            evidenceContract = evidenceContract,
            stepDefinitions = listOf(step),
        )
    }

    fun isCompatible(
        workflow: ResolvedWorkflow,
        workItemType: Int,
        workDefinition: WorkDefinitionManifest?,
        acceptanceContract: AcceptanceContract? = null,
    ): Boolean = workflow == resolve(workItemType, workDefinition, acceptanceContract) ||
        (workDefinition == null && workflow == legacy(workItemType))

    fun criterionEvidenceKind(criterionId: String): String = "CRITERION:$criterionId"

    private fun legacy(workItemType: Int): ResolvedWorkflow {
        require(workItemType == ENTITY_TASK || workItemType == ENTITY_BUG)
        val requirements = buildList {
            add(EvidenceRequirement("SOURCE_DIFF", "A source diff tied to the pinned repository revision."))
            if (workItemType == ENTITY_BUG) {
                add(EvidenceRequirement("REGRESSION_TEST", "A regression test that demonstrates the corrected defect."))
            }
            add(EvidenceRequirement("BUILD", "A successful build against the resulting revision."))
            add(EvidenceRequirement("TEST", "A successful relevant test suite against the resulting revision."))
        }
        return ResolvedWorkflow(
            id = "default-delivery-${if (workItemType == ENTITY_BUG) "bug" else "task"}",
            version = 1,
            workItemType = workItemType,
            steps = listOf("RECALL_CONTEXT", "EXECUTE", "VERIFY_EVIDENCE", "DECIDE_TRANSITION"),
            evidenceContract = EvidenceContract(
                id = "${if (workItemType == ENTITY_BUG) "bug" else "task"}-completion",
                version = 1,
                requirements = requirements,
            ),
        )
    }
}