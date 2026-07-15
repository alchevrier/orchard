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

    fun resolve(workItemType: Int): ResolvedWorkflow {
        require(workItemType == ENTITY_TASK || workItemType == ENTITY_BUG) { "Only tasks and bugs can start a workflow" }
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