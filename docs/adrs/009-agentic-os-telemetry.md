# ADR 009: Agentic Operating System - Processes, Syscalls, and Telemetry

## Status
Accepted

## Context
Orchard must move beyond being viewed as an "IDE" or a "Project Management Tool." It is an **Agentic Operating System**. To enforce governed, evidence-producing workflows correctly, we must map core Operating System concepts onto our AI pipeline.

## Decision
We define the following mappings for Orchard's architecture:

1. **The Kernel (Autumn)**: The low-level router. It manages memory (`AutumnMemoryBank`), IO (`GatewayDevices`), and routing intents lock-free.
2. **The File System (Vectors + Markdown)**: The persisted state of the world (`rag-shared`, `projects`).
3. **Processes (Agents)**: Autonomous LLM loops spawned to handle specific `Ticket` bounds.
4. **Permissions & Scheduling (Workflows)**: The `WorkflowIntent` acts as the security policy and scheduler. An agent process is only allowed to transition a ticket state if it meets the workflow's constraints.
5. **Stdout / Telemetry (Quality Center)**: Agents do not arbitrarily say "I am done." They must emit a `TelemetryIntent` (syscall) containing their test coverage, Playwright snapshot hashes, and performance benchmarks.

The OS kernel (Autumn) intercepts this `TelemetryIntent`. It compares the emitted telemetry against the `WorkflowIntent` success criteria.
- If the criteria pass -> The OS updates the ticket to Done.
- If the criteria fail -> The OS routes an error intent back to the Agent's local channel (like a `SIGTRAP` or `SIGSEGV`), forcing the agent to retry.

## Consequences
- The "Quality Center" is no longer just a UI tab; it is the fundamental telemetry ingest pipeline.
- Agents must be built with standard "syscall" interfaces to report their evidence natively to Autumn's boundary devices.