package com.orchard.backend.config

import kotlinx.serialization.Serializable

@Serializable
enum class OrchardOperationMode {
    AUTONOMOUS,
    PILOTED;

    val automaticModelDispatchEnabled: Boolean
        get() = this == AUTONOMOUS

    companion object {
        fun resolve(environment: Map<String, String> = System.getenv()): OrchardOperationMode {
            val configured = environment["ORCHARD_OPERATION_MODE"]?.trim()?.takeIf(String::isNotEmpty)
                ?: return AUTONOMOUS
            return entries.singleOrNull { it.name.equals(configured, ignoreCase = true) }
                ?: throw IllegalArgumentException(
                    "ORCHARD_OPERATION_MODE must be AUTONOMOUS or PILOTED, but was '$configured'.",
                )
        }
    }
}
