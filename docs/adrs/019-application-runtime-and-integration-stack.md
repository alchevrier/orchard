# ADR 019: Application Runtime and Integration Stack

## Status
Accepted

This ADR supersedes ADRs 001, 006, 009, 015, 017, and 018. It partially supersedes the Autumn-specific implementation decisions in ADRs 010, 011, 012, and 016.

## Context
Orchard's product is a filesystem-native engineering intelligence and execution system. Its dominant workloads are repository inspection, document processing, model inference, filesystem mutation, process execution, and user-facing workflow coordination. These operations are asynchronous, externally bounded, and measured in milliseconds or seconds rather than deterministic CPU cycles.

The current implementation uses Autumn as its application runtime, HTTP server and client, memory layout owner, JSON traversal mechanism, scheduler, and boundary abstraction. Building Orchard against Autumn has validated useful ideas, but it has also made Orchard responsible for adding general application capabilities to an experimental compiler/runtime before product features can be delivered. Examples include complete HTTP behavior, bounded outbound writers, nested serialization, persistent indexed storage, cancellation, and production connection lifecycle management.

Orchard does not currently have a user-visible workload whose correctness or value depends on cycle-level deterministic scheduling. Its correctness requirements come from typed validation, explicit authority, atomic filesystem changes, bounded retries, evidence gates, and auditable state transitions.

## Decision
Orchard will use a conventional Kotlin application stack:

1. **Kotlin** remains the implementation language.
2. **Compose Multiplatform** remains the desktop user-interface framework.
3. **Ktor** owns inbound HTTP, outbound model-provider calls, streaming, timeouts, cancellation, retries, and transport lifecycle.
4. **Kotlin coroutines and Flow** own asynchronous orchestration, structured concurrency, progress publication, and cancellation propagation.
5. **kotlinx.serialization** owns versioned JSON boundary and persistence formats.
6. **Filesystem repositories** own durable Orchard state as defined by ADR 020.
7. Model providers, process execution, repository readers, embedding engines, and search indexes are replaceable adapters behind Orchard-owned interfaces.

Autumn will not be a required Orchard runtime, compiler plugin, persistence format, transport, or in-process execution kernel. Orchard may inform Autumn's independent research roadmap, and a future optional adapter may use Autumn for a workload that demonstrates a concrete deterministic benefit. Orchard domain interfaces must not depend on Autumn types to preserve that option without coupling product delivery to it.

The following architectural principles remain independent of Autumn and continue to apply:

- Probabilistic model output proposes actions but does not directly mutate authoritative state.
- Domain commands are validated before mutation.
- Workflow transitions are explicit and auditable.
- Expensive or blocking operations have explicit ownership, cancellation, and evidence.
- Derived indexes and projections are rebuildable from authoritative files.

## Consequences
- Orchard features no longer require compiler/runtime work in another repository before implementation.
- Standard libraries provide mature HTTP, serialization, cancellation, testing, and operational behavior.
- Existing Autumn gateways, channels, memory-bank serializers, topology generation, schema locks, and frontend motherboard integration are replaced by conventional Kotlin components.
- The migration must preserve domain behavior and durable data rather than mechanically translate runtime abstractions.
- Orchard loses Autumn's generated fixed-memory topology and cycle-budget experiments, which are not current product requirements.
- Autumn remains an independent research project and can extract proven concepts from Orchard without controlling Orchard's release cadence.
- This ADR defines a target architecture, not an implementation order or release priority.