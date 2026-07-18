package com.orchard.backend.api

import com.orchard.backend.workspace.ConversationCommandReference

data class DocumentIntent(
    val actionTypeId: Int,
    val entityTypeId: Int,
    val boundWorkflowId: Long,
    val projectId: Int = 0,
    val epicId: Int = 0,
    val storyId: Int = 0,
    val title: String,
    val content: String = "",
    val conversationCommand: ConversationCommandReference? = null,
)
