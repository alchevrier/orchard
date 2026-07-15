# ADR 012: Deterministic Validation of Probabilistic LLMs

## Status
Accepted

## Context
Local LLMs (like those served via Ollama) are probabilistic engines. Even when prompted for strict JSON via structural constraints, they can hallucinate keys, generate invalid workflow ID hashes, or hallucinate project structures that don't physically exist in the local VectorWAL. If this dirty data enters the central Autumn pipeline, the OS will fault.

## Decision
We enforce a strict **Correction Loop** within the Intent Synthesizer FSM:

1. **Memory-Backed System Prompts**: The generation rules (e.g., "Output an array of DocumentIntents") are not hardcoded in Kotlin string literals. They are stored natively as Markdown files in the `rag-shared/prompts/` filesystem and loaded into the native string registry at boot.
2. **Phase 3 Validation**: When the Ollama gateway returns the generated JSON plan, Phase 3 of the FSM parses the native bytes. It verifies:
   - Does it match the `DocumentIntent` Schema?
   - Do the `projectIdHash` and `boundWorkflowIdHash` physically exist in the vector database?
3. **The Correction Rebound**: If the validation fails, the FSM state does *not* crash or alert the user immediately. Instead, Phase 3 modifies the payload buffer with the exact validation error (e.g., "Error: WorkflowHash 88321 does not exist. Choose from: [44123, 99123]") and reverts the struct's `synthesisPhase` back to `2` (Planning).
4. **Retry Budget**: The state contains a `retryCount` limit. If the LLM fails 3 times sequentially, *then* it gracefully downgrades into a UI error message.

## Consequences
- Transforms chaotic probabilistic outputs into 100% deterministic native structures.
- Makes the Agent self-healing without human intervention.
- Formalizes system prompts as human-readable configuration files in the Orchard ecosystem.