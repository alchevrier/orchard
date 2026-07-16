package com.orchard.backend.resource

import com.sun.management.OperatingSystemMXBean
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.Serializable

@Serializable
data class MachineUsagePolicy(
    val capacityPercent: Int = 100,
    val minimumFreeMemoryBytes: Long = 1_073_741_824,
    val maxConcurrentModelExecutions: Int = 1,
)

@Serializable
data class MachineCapacitySnapshot(
    val totalMemoryBytes: Long,
    val availableMemoryBytes: Long,
    val logicalProcessors: Int,
    val systemCpuLoad: Double?,
    val observedAt: String = Instant.now().toString(),
)

@Serializable
data class ModelResourceDemand(
    val memoryBytes: Long,
    val cpuUnits: Int = 1,
)

@Serializable
data class ResourceAdmissionEvidence(
    val decision: ResourceAdmissionDecision,
    val reason: String,
    val policy: MachineUsagePolicy,
    val capacity: MachineCapacitySnapshot,
    val demand: ModelResourceDemand,
    val reservedMemoryBytes: Long,
    val reservedCpuUnits: Int,
    val activeLeases: Int,
)

@Serializable
enum class ResourceAdmissionDecision {
    ADMITTED,
    POLICY_CAPACITY_EXCEEDED,
    LIVE_CAPACITY_EXCEEDED,
    CONCURRENCY_EXCEEDED,
    TELEMETRY_UNAVAILABLE,
}

@Serializable
data class MachineResourceConfiguration(
    val policy: MachineUsagePolicy,
    val capacity: MachineCapacitySnapshot,
    val reservedMemoryBytes: Long,
    val reservedCpuUnits: Int,
    val activeLeases: Int,
    val lastAdmission: ResourceAdmissionEvidence? = null,
)

enum class MachineUsagePolicyUpdateStatus {
    UPDATED,
    INVALID_POLICY,
    STORAGE_UNAVAILABLE,
    TELEMETRY_UNAVAILABLE,
}

data class MachineUsagePolicyUpdateResult(
    val status: MachineUsagePolicyUpdateStatus,
    val configuration: MachineResourceConfiguration? = null,
)

interface MachineCapacityMonitor {
    fun snapshot(): MachineCapacitySnapshot
}

class SystemMachineCapacityMonitor(
    private val memInfoPath: Path = Path.of("/proc/meminfo"),
    private val processCgroupPath: Path = Path.of("/proc/self/cgroup"),
    private val cgroupRoot: Path = Path.of("/sys/fs/cgroup"),
    private val cgroupV1MemoryRoot: Path = Path.of("/sys/fs/cgroup/memory"),
) : MachineCapacityMonitor {
    override fun snapshot(): MachineCapacitySnapshot {
        val operatingSystem = ManagementFactory.getOperatingSystemMXBean() as? OperatingSystemMXBean
        val memInfo = readMemInfo()
        val hostTotal = memInfo["MemTotal"] ?: operatingSystem?.totalMemorySize ?: 0
        val hostAvailable = memInfo["MemAvailable"] ?: operatingSystem?.freeMemorySize ?: 0
        require(hostTotal > 0 && hostAvailable >= 0) { "Physical memory telemetry is unavailable" }
        val cgroupBoundaries = resolveProcessCgroupV2()?.let(::cgroupV2MemoryBoundaries)
            ?: resolveProcessCgroupV1()?.let(::cgroupV1MemoryBoundaries)
            ?: emptyList()
        val cgroupMaximum = cgroupBoundaries.map { it.first }.minOrNull()
        val cgroupAvailable = cgroupBoundaries.map { it.second }.minOrNull()
        val total = cgroupMaximum?.coerceAtMost(hostTotal) ?: hostTotal
        val available = cgroupAvailable?.let { minOf(hostAvailable, it) } ?: hostAvailable
        val cpuLoad = operatingSystem?.cpuLoad?.takeIf { it.isFinite() && it >= 0.0 }
        return MachineCapacitySnapshot(
            totalMemoryBytes = total,
            availableMemoryBytes = available.coerceAtMost(total),
            logicalProcessors = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
            systemCpuLoad = cpuLoad?.coerceIn(0.0, 1.0),
        )
    }

    private fun readMemInfo(): Map<String, Long> = if (Files.isReadable(memInfoPath)) {
        Files.readAllLines(memInfoPath).mapNotNull { line ->
            val separator = line.indexOf(':')
            if (separator <= 0) return@mapNotNull null
            val kibibytes = line.substring(separator + 1).trim().substringBefore(' ').toLongOrNull()
                ?: return@mapNotNull null
            line.substring(0, separator) to kibibytes * 1_024
        }.toMap()
    } else {
        emptyMap()
    }

    private fun readLong(path: Path): Long? = runCatching {
        Files.readString(path).trim().takeUnless { it == "max" }?.toLong()
    }.getOrNull()

    private fun resolveProcessCgroupV2(): Path? {
        if (!Files.isReadable(processCgroupPath) || !Files.exists(cgroupRoot.resolve("cgroup.controllers"))) return null
        val relative = Files.readAllLines(processCgroupPath)
            .firstOrNull { it.startsWith("0::") }
            ?.substringAfter("0::")
            ?.removePrefix("/")
            ?: return null
        val resolved = cgroupRoot.resolve(relative).normalize()
        require(resolved.startsWith(cgroupRoot)) { "Invalid process cgroup path" }
        return resolved
    }

    private fun cgroupV2MemoryBoundaries(processCgroup: Path): List<Pair<Long, Long>> {
        val boundaries = mutableListOf<Pair<Long, Long>>()
        var current = processCgroup
        while (current.startsWith(cgroupRoot)) {
            val maximum = readLong(current.resolve("memory.max"))
            if (maximum != null) {
                val usage = requireNotNull(readLong(current.resolve("memory.current"))) {
                    "Cgroup memory usage is unavailable at $current"
                }
                boundaries += maximum to (maximum - usage).coerceAtLeast(0)
            }
            if (current == cgroupRoot) break
            current = current.parent ?: break
        }
        return boundaries
    }

    private fun resolveProcessCgroupV1(): Path? {
        if (!Files.isReadable(processCgroupPath) || !Files.isDirectory(cgroupV1MemoryRoot)) return null
        val relative = Files.readAllLines(processCgroupPath)
            .firstOrNull { line -> line.substringBefore(':').isNotBlank() && line.substringAfter(':').substringBefore(':').split(',').contains("memory") }
            ?.substringAfterLast(':')
            ?.removePrefix("/")
            ?: return null
        val resolved = cgroupV1MemoryRoot.resolve(relative).normalize()
        require(resolved.startsWith(cgroupV1MemoryRoot)) { "Invalid process cgroup v1 path" }
        return resolved
    }

    private fun cgroupV1MemoryBoundaries(processCgroup: Path): List<Pair<Long, Long>> {
        val boundaries = mutableListOf<Pair<Long, Long>>()
        var current = processCgroup
        while (current.startsWith(cgroupV1MemoryRoot)) {
            val maximum = readLong(current.resolve("memory.limit_in_bytes"))
            if (maximum != null) {
                val usage = requireNotNull(readLong(current.resolve("memory.usage_in_bytes"))) {
                    "Cgroup v1 memory usage is unavailable at $current"
                }
                boundaries += maximum to (maximum - usage).coerceAtLeast(0)
            }
            if (current == cgroupV1MemoryRoot) break
            current = current.parent ?: break
        }
        return boundaries
    }
}

interface MachineUsagePolicyStore {
    fun load(): MachineUsagePolicy
    fun save(policy: MachineUsagePolicy)
}

class TransientMachineUsagePolicyStore(
    initial: MachineUsagePolicy = MachineUsagePolicy(),
) : MachineUsagePolicyStore {
    private var policy = initial

    @Synchronized
    override fun load(): MachineUsagePolicy = policy

    @Synchronized
    override fun save(policy: MachineUsagePolicy) {
        this.policy = policy
    }
}

class MachineResourceController(
    private val policyStore: MachineUsagePolicyStore,
    private val monitor: MachineCapacityMonitor,
) {
    private var reservedMemoryBytes = 0L
    private var reservedCpuUnits = 0
    private var activeLeases = 0
    private var lastAdmission: ResourceAdmissionEvidence? = null

    @Synchronized
    fun configuration(): MachineResourceConfiguration {
        val policy = policyStore.load()
        val capacity = monitor.snapshot()
        return MachineResourceConfiguration(policy, capacity, reservedMemoryBytes, reservedCpuUnits, activeLeases, lastAdmission)
    }

    @Synchronized
    fun updatePolicy(policy: MachineUsagePolicy): MachineUsagePolicyUpdateResult {
        val currentPolicy = runCatching { policyStore.load() }.getOrElse {
            return MachineUsagePolicyUpdateResult(MachineUsagePolicyUpdateStatus.STORAGE_UNAVAILABLE)
        }
        val capacity = runCatching { monitor.snapshot() }.getOrElse {
            return MachineUsagePolicyUpdateResult(MachineUsagePolicyUpdateStatus.TELEMETRY_UNAVAILABLE)
        }
        if (!valid(policy, capacity)) {
            return MachineUsagePolicyUpdateResult(
                MachineUsagePolicyUpdateStatus.INVALID_POLICY,
                current(currentPolicy, capacity),
            )
        }
        return try {
            policyStore.save(policy)
            MachineUsagePolicyUpdateResult(MachineUsagePolicyUpdateStatus.UPDATED, current(policy, capacity))
        } catch (_: Exception) {
            MachineUsagePolicyUpdateResult(MachineUsagePolicyUpdateStatus.STORAGE_UNAVAILABLE)
        }
    }

    @Synchronized
    fun tryAcquire(demand: ModelResourceDemand): ResourceAdmissionResult {
        if (demand.memoryBytes < 0 || demand.cpuUnits <= 0) {
            return denied(ResourceAdmissionDecision.TELEMETRY_UNAVAILABLE, "Provider resource demand is invalid", demand)
        }
        val policy = runCatching { policyStore.load() }.getOrElse {
            return denied(ResourceAdmissionDecision.TELEMETRY_UNAVAILABLE, "Machine usage policy is unavailable", demand)
        }
        val capacity = runCatching { monitor.snapshot() }.getOrElse {
            return denied(ResourceAdmissionDecision.TELEMETRY_UNAVAILABLE, "Live machine telemetry is unavailable", demand, policy)
        }
        if (activeLeases >= policy.maxConcurrentModelExecutions) {
            return denied(ResourceAdmissionDecision.CONCURRENCY_EXCEEDED, "Model execution concurrency is exhausted", demand, policy, capacity)
        }
        val cpuLoad = capacity.systemCpuLoad ?: return denied(
            ResourceAdmissionDecision.TELEMETRY_UNAVAILABLE,
            "CPU telemetry is unavailable",
            demand,
            policy,
            capacity,
        )
        val memoryLimit = percentage(capacity.totalMemoryBytes, policy.capacityPercent)
        val cpuLimit = percentage(capacity.logicalProcessors.toLong(), policy.capacityPercent).coerceAtLeast(1).toInt()
        val requestedMemory = saturatingAdd(reservedMemoryBytes, demand.memoryBytes)
        val requestedCpu = saturatingAdd(reservedCpuUnits, demand.cpuUnits)
        if (requestedMemory > memoryLimit || requestedCpu > cpuLimit) {
            return denied(ResourceAdmissionDecision.POLICY_CAPACITY_EXCEEDED, "The user-delegated machine share is exhausted", demand, policy, capacity)
        }
        val safelyAvailableMemory = (capacity.availableMemoryBytes - policy.minimumFreeMemoryBytes).coerceAtLeast(0)
        val liveCpuUnits = (capacity.logicalProcessors * (1.0 - cpuLoad)).toInt().coerceAtLeast(0)
        if (requestedMemory > safelyAvailableMemory || requestedCpu > liveCpuUnits) {
            return denied(ResourceAdmissionDecision.LIVE_CAPACITY_EXCEEDED, "Observed machine capacity cannot satisfy the execution", demand, policy, capacity)
        }
        reservedMemoryBytes = requestedMemory
        reservedCpuUnits = requestedCpu
        activeLeases++
        val evidence = evidence(ResourceAdmissionDecision.ADMITTED, "Resource lease granted", demand, policy, capacity)
        lastAdmission = evidence
        return ResourceAdmissionResult(ResourceLease(this, demand), evidence)
    }

    @Synchronized
    private fun release(demand: ModelResourceDemand) {
        reservedMemoryBytes = (reservedMemoryBytes - demand.memoryBytes).coerceAtLeast(0)
        reservedCpuUnits = (reservedCpuUnits - demand.cpuUnits).coerceAtLeast(0)
        activeLeases = (activeLeases - 1).coerceAtLeast(0)
    }

    private fun denied(
        decision: ResourceAdmissionDecision,
        reason: String,
        demand: ModelResourceDemand,
        policy: MachineUsagePolicy = MachineUsagePolicy(),
        capacity: MachineCapacitySnapshot = MachineCapacitySnapshot(0, 0, 1, null),
    ): ResourceAdmissionResult {
        val evidence = evidence(decision, reason, demand, policy, capacity)
        lastAdmission = evidence
        return ResourceAdmissionResult(null, evidence)
    }

    private fun evidence(
        decision: ResourceAdmissionDecision,
        reason: String,
        demand: ModelResourceDemand,
        policy: MachineUsagePolicy,
        capacity: MachineCapacitySnapshot,
    ) = ResourceAdmissionEvidence(
        decision,
        reason,
        policy,
        capacity,
        demand,
        reservedMemoryBytes,
        reservedCpuUnits,
        activeLeases,
    )

    private fun current(policy: MachineUsagePolicy, capacity: MachineCapacitySnapshot) =
        MachineResourceConfiguration(policy, capacity, reservedMemoryBytes, reservedCpuUnits, activeLeases, lastAdmission)

    private fun valid(policy: MachineUsagePolicy, capacity: MachineCapacitySnapshot): Boolean =
        policy.capacityPercent in 1..100 &&
            policy.minimumFreeMemoryBytes >= 0 &&
            policy.minimumFreeMemoryBytes < capacity.totalMemoryBytes &&
            policy.maxConcurrentModelExecutions in 1..capacity.logicalProcessors

    private fun percentage(value: Long, percent: Int): Long = value / 100 * percent + value % 100 * percent / 100

    private fun saturatingAdd(left: Long, right: Long): Long = if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

    private fun saturatingAdd(left: Int, right: Int): Int = if (Int.MAX_VALUE - left < right) Int.MAX_VALUE else left + right

    companion object {
        fun unrestricted(): MachineResourceController = MachineResourceController(
            TransientMachineUsagePolicyStore(MachineUsagePolicy(100, 0, Int.MAX_VALUE)),
            object : MachineCapacityMonitor {
                override fun snapshot() = MachineCapacitySnapshot(Long.MAX_VALUE, Long.MAX_VALUE, Int.MAX_VALUE, 0.0)
            },
        )
    }

    class ResourceLease internal constructor(
        private val controller: MachineResourceController,
        private val demand: ModelResourceDemand,
    ) : AutoCloseable {
        private val closed = AtomicBoolean()

        override fun close() {
            if (closed.compareAndSet(false, true)) controller.release(demand)
        }
    }
}

data class ResourceAdmissionResult(
    val lease: MachineResourceController.ResourceLease?,
    val evidence: ResourceAdmissionEvidence,
)