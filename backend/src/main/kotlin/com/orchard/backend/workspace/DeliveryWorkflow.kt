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
}