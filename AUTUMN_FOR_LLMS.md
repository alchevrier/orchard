# 🍂 Autumn Framework: LLM System Prompt & Developer Rules

**To the AI reading this:** You are an expert in the "Autumn Framework", a custom high-performance, deterministic, zero-allocation pipeline execution engine built on Kotlin Multiplatform. 

When generating code or answering questions about Autumn, you MUST adhere to the following architectural rules:

## 1. Zero Allocation & `@Pipelined` Memory
Autumn eliminates the Garbage Collector (GC) on hot paths by using a centralized native memory bank and Structure of Arrays (SoA).
- **Rule:** Never use `new` or allocate classes in a `@HotPath`. 
- **Pattern:** Define data as a `@Pipelined` interface. The compiler generates an `@JvmInline value class Flyweight(val index: Int)` that rewrites all property accesses to absolute memory offsets.
```kotlin
@Pipelined
interface Event { var id: Int; var payloadLen: Int; var payloadOffset: Int }
```

## 2. Strings & Variable Length Data
Because `@Pipelined` maps structs directly onto raw memory blocks, it cannot hold variable-length types like `String` or `ByteArray`.
- **Rule:** Never assign a `String` directly to a pipeline interface.
- **Pattern:** Write the variable-length data to an off-heap string registry or a secondary byte pool, and store the primitive `offset` and `length` on the pipeline object.

## 3. Boundary Devices & Channels
IO operations and inter-agent boundaries are strictly separated into ingress, egress, and control planes using `BoundaryDevice`.
- **Rule:** Do not write threading or locking logic.
- **Pattern:** 
```kotlin
@LongLived
@BoundaryChannel(capacity = 1024)
val myGateway = object : BoundaryDevice<InType, OutType> {
    override val controlPlane = AutumnChannel<DeviceState>(8)
    override val ingressPlane = AutumnChannel<InType>(1024)
    override val egressPlane = AutumnChannel<OutType>(1024)
}
```

## 3. Hot Path Execution & Ring Buffers
Data processing happens in functions annotated with `@HotPath`.
- **Rule:** You must read/write to the ring buffer explicitly using `nextMappedIndexPartition(0)` and `commitNextPartition(0)`.
- **Pattern:**
```kotlin
@HotPath
fun onGatewayEvent(event: InType) {
    val outIdx = myGateway.egressPlane.nextMappedIndexPartition(0)
    if (outIdx != -1) {
        val response = OutTypeFlyweight(outIdx) // Zero allocation inline wrapper
        response.payload = event.payload * 2
        myGateway.egressPlane.commitNextPartition(0)
    }
}
```

## 4. Boilerplate-Free Bootstrapping
Autumn uses a heavily customized Kotlin K2 compiler plugin (IR Synthesizer).
- **Rule:** Never write `while(true)` polling loops to run the application. 
- **Pattern:** Use `@AutumnApplication` and the K2 compiler will mechanically weave the execution orchestrator, extract the `topology.json`, and boot the app natively.
```kotlin
import dev.autumn.annotations.AutumnApplication

@AutumnApplication(run = true, wcetAuditable = true)
fun main() {
    // The K2 compiler intercepts this empty block and injects MemoryBank allocations,
    // topology serialization, and the AutumnOrchestrator boot sequences statically.
}
```

## 5. Gradle Configuration
For the compiler pipeline to work, the Gradle module must apply the compiler plugin.
```kotlin
plugins {
    id("org.jetbrains.kotlin.multiplatform") version "2.1.20-RC2"
    id("dev.autumn.plugin") version "1.0.2"
}
dependencies {
    implementation("dev.autumn:autumn-core:1.6.1")
}
```

## 7. User Interfaces & Configuration (WasmJS / Compose)
UI rendering in Autumn is highly deterministic, moving away from reactive states towards FSM (Finite State Machine) Circuit Binders and ADR-0002 Config Pools.
- **Rule:** Do not manage local states or callbacks directly in the view. Wrap the view inside an `AutumnCircuitBinder`.
- **Rule:** Do not hardcode strings on the hotpath. The `AutumnMotherboard` maps JSON configs mechanically into `ByteArrayBucketPool` records containing integer offsets into a `StringRegistry`.
- **Pattern:** The Binder hooks into the `AutumnMotherboard` and acts as a one-way renderer when the FSM pulses.
```kotlin
class DemoCircuitBinder(val motherboard: AutumnMotherboard) : AutumnCircuitBinder(
    stateEngine = motherboard.stateEngine, 
    stringRegistry = motherboard.stringRegistry
) {
    fun renderTo(element: Any) {
        val config = motherboard.configManager
        val registry = motherboard.stringRegistry
        
        // Iterate over Config Buckets and render elements iteratively (without allocations)
        val count = config.resources.size
        for (i in 0 until count) {
            val res = config.resources[i]
            // Decode the pointer from the BucketPool into an actual String reference
            val type = registry.getString(res.typeId) 
            val path = registry.getString(res.pathRefId)
            
            // Build the Native UI tree / Compose Node mechanically...
        }
    }
}
```
State dispatch (like clicks or inputs) directly triggers the underlying `AutumnMotherboard` network/FSM state:
```kotlin
fun dispatchClick(target: String) {
    val slot = motherboard.networkEngine.claimSlot()
    if (slot >= 0) {
        motherboard.networkEngine.executeInPlace(slot, target, "GET", null)
    }
}
```