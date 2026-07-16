# ADR 027: Collaborative Work Definition Authority

## Status

Accepted

## Context

Tasks and Bugs need low-ambiguity Work Definitions before delivery begins. A final human approval gate is insufficient because useful definitions emerge through iteration: a human may correct assumptions, an LLM may synthesize a revision, and either participant may expose missing context.

That collaboration must not make model output authoritative. Orchard also needs to preserve rejected directions and the evidence behind every accepted definition across process restarts.

## Decision

The Work Definition step is a durable collaboration between HUMAN, LOCAL_LLM, and DETERMINISTIC_POLICY actors.

Its interaction contract grants:

- HUMAN: propose, revise, provide feedback, and accept.
- LOCAL_LLM: propose and revise.
- DETERMINISTIC_POLICY: assess completeness and derive the workflow signal.

Every proposal is immutable and append-only. A revision creates a new proposal with its own actor, revision number, content hash, and parent context. Human feedback is a separate append-only artifact and is recalled by subsequent model executions.

Model proposals preserve observations separately from assumptions. They also record executor identity, model identity, prompt version, and hashes of the prompt, recalled context, and raw output.

Human edits made during acceptance create a distinct HUMAN proposal. Acceptance pins the selected proposal ID and hash into the resulting Work Definition. Deterministic assessment then derives READY, CLARIFICATION_REQUIRED, INVESTIGATION_REQUIRED, or SPLIT_REQUIRED. The model cannot emit an authoritative transition.

Collaboration closes when a delivery run starts. Proposals, feedback, acceptance, and provenance are recovered from a checksummed file-backed journal.

## Consequences

- Humans and local models can iterate without conflating contribution with authority.
- Rejected and superseded directions remain recoverable engineering history.
- Accepted definitions are traceable to exact immutable source content.
- Model availability affects proposal generation only; it cannot block human definition or weaken deterministic governance.
- Other collaborative workflow steps can reuse the actor/action interaction contract.

## Alternatives Considered

### Model-generated definition followed by one approval

Rejected because it reduces human participation to a terminal gate and loses the reasoning produced during iteration.

### Model signals READY

Rejected because probabilistic output must not control workflow authority.

### Overwrite the latest draft

Rejected because it destroys provenance and makes accepted decisions impossible to reconstruct.

### Treat human edits as edits to an LLM proposal

Rejected because authorship and responsibility would become ambiguous.
