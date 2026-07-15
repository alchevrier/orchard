# ADR 016: Coding Center Agent Architecture

## Context
Orchard requires an execution engine to translate validated `DocumentIntent` JSON structures (Projects, Workflows, Tickets) created by the Architect Agent into actual source code changes on the user's filesystem.

## Decision
We will implement a `CodingCenterGateway` that reads validated Ticket intents from the `VectorWAL` and orchestrates a local LLM to execute code generation.

The Coding Center Agent pipeline will follow these phases (FSM):
1. **Context Assembly**: The OS loops gather the Target File(s), Ticket Description, and associated Workflow Rules.
2. **Drafting (LLM)**: The Coding Agent generates a unified diff or raw source code replacement block.
3. **Verification (Heuristic + Compiler)**: The OS applies the generated diff to a temporary memory buffer. If possible, it triggers a background lint/compile action.
4. **Commit/Revert**: If verification passes, the OS writes the modification to disk and marks the Ticket as complete. If it fails, the OS re-queues the LLM with the error logs as feedback (up to a max retry limit).

## Consequences
- **Security Check**: This process modifies actual workspace files. We need a clear "Auto-Apply" vs "Human Approval" boundary governed by the ticket or project settings.
- **Resource Constraints**: Generating code requires a slightly larger context window than Triage and Planning, so this phase demands more hardware resources or a slightly larger Ollama model (e.g., Llama 3 8B instead of a 3B parameter model).
- **Zero-Allocation Execution**: The memory buffer for the diff evaluation will utilize Autumn's `@VirtualBuffer` structures to prevent large string allocations from crashing the JVM.

## Alignment with Local OS Paradigm
This agent doesn't "chat" about coding; it acts like a background compilation step. The user schedules work on the queue (via Architect Chat), and the OS silently completes it locally when idle compute is available.
