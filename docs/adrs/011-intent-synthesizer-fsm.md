# ADR 011: Intent Synthesizer FSM (The Sub-Agent Architecture)

## Status
Accepted

## Context
When the UI passes a freeform chat prompt into the `ArchitectAgentGateway`, the system cannot reliably map natural language directly into authoritative Autumn `DocumentIntent` records in a single LLM shot. A request may contain several dependent mutations, while probabilistic output can omit structural entities, invent IDs, or produce invalid parent relationships.

## Decision
The Architect chat handler acts as an **Intent Synthesizer pipeline**, breaking interpretation and mutation into bounded state transitions:

1. **Triage**: Ask Ollama for the primary action, primary entity, requested operation count, and whether the request is a batch.
2. **Plan generation**: Ask Ollama for an ordered `operations[]` array containing symbolic action and entity labels, exact user text, and backward-only `parentOperationIndex` references. A plan contains at most eight operations.
3. **Deterministic validation**: Parse the bounded JSON, reject unknown labels and malformed operations, preserve explicit user titles and descriptions, and resolve existing parent IDs from the request and workspace facts.
4. **Workflow normalization**: Kotlin enforces `Project -> Epic -> Story -> Task/Bug`, assigns entity IDs and parent edges, and inserts the workflow-owned `General` epic when a new project and story have no explicit epic.
5. **Atomic dispatch**: Begin a workspace batch, dispatch each normalized operation through `DocumentIntent`, and roll back every staged entity if any operation fails. Only a fully valid batch becomes visible to the resource matrix.

Ollama is responsible for semantic interpretation and ordering only. It is never authoritative for IDs, hierarchy, workflow insertion, transaction boundaries, or durable state.

## Consequences
- One sentence can create several dependent entities without weakening workflow validation.
- A malformed child cannot leave a partially created project or hierarchy behind.
- Deterministic normalization can absorb bounded model defects, such as a missing structural epic title, without accepting blank user-owned entities.
- The fixed operation limit and structure-of-arrays staging keep memory and execution bounds explicit.
- The current workspace authority remains in memory until the filesystem WAL and snapshot are implemented; the vector WAL is a derived semantic index, not hierarchy authority.
- The phases remain native Autumn channel transitions so the Ollama boundary does not turn the hot path into a suspended object graph.