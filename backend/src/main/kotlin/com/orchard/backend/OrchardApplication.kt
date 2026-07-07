package com.orchard.backend

import dev.autumn.annotations.AutumnApplication
import com.orchard.backend.config.OrchardPaths

@AutumnApplication(run = true, wcetAuditable = true)
fun main() {
    println("Booting Orchard Application...")
    OrchardPaths.initialize()
    
    // The K2 compiler intercepts this block and injects MemoryBank allocations,
    // topology serialization, and the AutumnOrchestrator boot sequences statically.
}
