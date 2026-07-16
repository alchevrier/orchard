# ADR 030: Resource-Aware Parallel Admission

## Status

Accepted

## Context

Execution profiles describe the reasoning aperture a workflow needs, and model bindings describe theoretical compatibility. Neither proves that the local machine can run another invocation now or that the user has delegated enough of the machine to Orchard.

Parallel ticket execution must therefore be constrained by actual availability and explicit user policy. Model processes cannot regulate their own admission because concurrent requests can observe the same free capacity and oversubscribe it.

## Decision

Orchard owns a machine usage policy containing:

- a percentage of total machine capacity delegated to Orchard
- a minimum free-memory reserve
- a maximum number of concurrent model executions

The policy is checksummed and atomically replaced in `machine-usage-policy.json` beneath the workspace authority directory.

Immediately before inference, Orchard samples host and process-cgroup capacity and atomically attempts to acquire a resource lease. For each resource, admission must fit both:

1. the unused portion of the user-delegated share of total capacity
2. observed available capacity after the configured safety reserve

Existing leases count toward both checks. Unknown CPU or memory telemetry fails closed. A lease is released on success, provider failure, or coroutine cancellation.

Linux memory telemetry combines `/proc/meminfo` with process-relative cgroup v2 or v1 limits. Orchard evaluates finite limits from the process cgroup through its ancestors and applies the most restrictive total and available boundary.

Ollama demand is currently a conservative model-residency estimate plus KV-cache bytes for the effective input and output aperture. Ollama receives both the effective `num_ctx` and an explicit `num_thread` matching its CPU reservation.

The resource controller is shared by Architect and Work Definition inference. Independent tickets use distinct keyed locks and may execute concurrently; duplicate generation for one ticket remains serialized.

Admission evidence records the policy, capacity snapshot, estimated demand, existing reservations, and decision with successful model execution observations. Capacity and concurrency exhaustion are retryable `429` outcomes; unavailable telemetry or policy authority is `503`.

## Consequences

- A declared context window no longer implies that execution is currently admissible.
- A 20% policy remains binding even when the machine is otherwise idle.
- A 100% policy cannot consume memory reserved by the user or memory already unavailable to the process.
- Concurrent requests cannot claim the same capacity because lease accounting is synchronized.
- Smaller effective apertures reduce both requested Ollama context and estimated KV-cache demand.
- Parallel throughput can increase safely as available capacity and user policy permit.

## Boundaries

- GPU/VRAM utilization, accelerator placement, thermal limits, and backend residency telemetry are not yet measured.
- The conservative memory estimate may reject work that could share an already resident model. It must not be treated as exact measured consumption.
- Orchard does not create or modify host cgroups. It observes inherited limits and constrains Ollama threads.
- Admission is immediate. Durable queues, priorities, dependency-aware scheduling, worktree isolation, and integration ordering remain future milestones.
- Lowering policy stops new admission but does not preempt an existing lease.

## Alternatives Considered

### Use only model context capacity

Rejected because compatibility does not represent current RAM, CPU pressure, or concurrent reservations.

### Use only currently free capacity

Rejected because it ignores the portion of the machine the user is willing to delegate and races under concurrent requests.

### Let Ollama schedule requests

Rejected because Orchard would lose deterministic admission evidence and could not coordinate model execution with other governed ticket work.

### Automatically reduce aperture under pressure

Rejected because silent aperture changes weaken reproducibility. Orchard queues or rejects the current request; changing the requested profile remains explicit user or workflow policy.