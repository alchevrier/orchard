package com.orchard.backend

import com.orchard.backend.config.OrchardOperationMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OrchardOperationModeTest {
    @Test
    fun `operation mode defaults to autonomous`() {
        assertEquals(OrchardOperationMode.AUTONOMOUS, OrchardOperationMode.resolve(emptyMap()))
        assertTrue(OrchardOperationMode.AUTONOMOUS.automaticModelDispatchEnabled)
    }

    @Test
    fun `piloted mode disables automatic model dispatch`() {
        val mode = OrchardOperationMode.resolve(mapOf("ORCHARD_OPERATION_MODE" to "piloted"))

        assertEquals(OrchardOperationMode.PILOTED, mode)
        assertFalse(mode.automaticModelDispatchEnabled)
    }

    @Test
    fun `invalid operation mode fails fast`() {
        assertFailsWith<IllegalArgumentException> {
            OrchardOperationMode.resolve(mapOf("ORCHARD_OPERATION_MODE" to "hybrid"))
        }
    }
}
