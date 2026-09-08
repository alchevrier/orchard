package com.orchard.backend.workspace

import com.orchard.backend.vector.MODEL_CAPABILITY_STRICT_JSON
import com.orchard.backend.vector.ModelBindingProfile
import com.orchard.backend.vector.ModelExecutionProfile
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ModelExperienceStoreTest {
    @Test
    fun `legacy model execution decodes without attention frame hash`() {
        val execution = execution(attentionFrameHash = null)
        val encoded = Json.encodeToString(execution)

        assertFalse(encoded.contains("attentionFrameHash"))
        assertEquals(execution, Json.decodeFromString<ModelExecutionObservation>(encoded))
    }

    @Test
    fun `model experience store replays attention frame hash`() {
        val directory = createTempDirectory("orchard-attention-provenance-")
        val execution = execution(attentionFrameHash = "d".repeat(64))
        val store = FileModelExperienceStore(directory)

        store.appendEvent(ModelExperienceEvent(1, execution = execution))

        assertEquals("d".repeat(64), FileModelExperienceStore(directory).loadEvents().single().execution?.attentionFrameHash)
    }

    private fun execution(attentionFrameHash: String?) = ModelExecutionObservation(
        executionId = 1,
        profile = ModelExecutionProfile("attention-test", 1, "BOUNDED_CODING", 4_096, 1_024, setOf(MODEL_CAPABILITY_STRICT_JSON)),
        binding = ModelBindingProfile("test:model", "test", "model", 8_192, setOf(MODEL_CAPABILITY_STRICT_JSON)),
        workflowStepId = "DELIVER_CHANGE:CODING_PATCH",
        workItemId = 9,
        envelopeHash = "a".repeat(64),
        promptHash = "b".repeat(64),
        outputHash = "c".repeat(64),
        inputTokens = 1_000,
        outputTokens = 100,
        latencyMillis = 10,
        schemaValid = true,
        recordedAt = "2026-09-04T00:00:00Z",
        attentionFrameHash = attentionFrameHash,
    )
}