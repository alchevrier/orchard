package com.orchard.backend.workspace

import kotlinx.serialization.Serializable

@Serializable
data class ConversationCommandReference(
    val commandId: Long,
    val commandHash: String,
    val capabilityId: String,
)