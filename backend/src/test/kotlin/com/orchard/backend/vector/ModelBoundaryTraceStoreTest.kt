package com.orchard.backend.vector

import com.orchard.backend.workspace.stagedPlanHash
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class ModelBoundaryTraceStoreTest {
    @Test
    fun `private trace bounds Unicode payloads without losing original identity and refuses file quota overflow`() {
        val directory = createTempDirectory("orchard-model-trace-")
        val store = ModelBoundaryTraceStore(directory, maxPayloadBytes = 5, maxTraces = 1)
        val payload = "a\u4f60\uD83D\uDE80x"
        store.newTrace(34).record("MODEL_GENERATION", payload)
        val path = Files.list(directory).use { it.toList().single() }
        val event = Json.decodeFromString<ModelBoundaryTraceEvent>(Files.readString(path))
        assertEquals("a\u4f60", event.payload)
        assertEquals(4, event.capturedBytes)
        assertEquals(payload.toByteArray().size, event.originalBytes)
        assertEquals(stagedPlanHash(payload), event.payloadHash)
        assertTrue(event.truncated)
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(path))
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(directory))
        val error = assertFailsWith<ModelBoundaryTraceException> { store.newGenesisTrace(44).record("MODEL_GENERATION", "next") }
        assertTrue(error.message.orEmpty().contains("file quota"))
        assertEquals(1L, Files.list(directory).use { it.count() })
    }

    @Test
    fun `trace byte quota fails explicitly without writing a partial JSON record`() {
        val directory = createTempDirectory("orchard-model-trace-byte-quota-")
        val store = ModelBoundaryTraceStore(directory, maxTraceBytes = 1)
        val error = assertFailsWith<ModelBoundaryTraceException> { store.newTrace(34).record("ANALYSIS_REQUEST", "request") }
        assertTrue(error.message.orEmpty().contains("byte quota"))
        assertEquals(0L, Files.size(Files.list(directory).use { it.toList().single() }))
    }
}
