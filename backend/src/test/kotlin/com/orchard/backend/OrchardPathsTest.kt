package com.orchard.backend

import com.orchard.backend.config.OrchardPaths
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class OrchardPathsTest {
    @Test
    fun resolveBaseDirPrefersExplicitOrchardHome() {
        val baseDir = OrchardPaths.resolveBaseDir(
            environment = mapOf(
                "ORCHARD_HOME" to "/tmp/orchard-home",
                "HOME" to "/tmp/legacy-home",
            ),
            userHome = "/tmp/default-home",
        )

        assertEquals(Path.of("/tmp/orchard-home/.orchard"), baseDir)
    }

    @Test
    fun resolveBaseDirFallsBackToHomeDirectory() {
        val baseDir = OrchardPaths.resolveBaseDir(
            environment = mapOf("HOME" to "/tmp/legacy-home"),
            userHome = "/tmp/default-home",
        )

        assertEquals(Path.of("/tmp/legacy-home/.orchard"), baseDir)
    }
}
