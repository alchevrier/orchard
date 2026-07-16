# ADR 025: System Workflows and Work Definition

## Status
Accepted

## Context
Delivery workflow correctness depends on the quality of its Task or Bug. Ambiguous work makes context retrieval noisy, permits implementation agents to invent requirements, weakens evidence contracts, makes review subjective, and produces misleading episodic memory.

Milestone 4 governed execution after admission, but admission only required an existing Task or Bug and a clean committed repository. Orchard needs an earlier authority boundary that determines what completion means before any implementation workflow starts.

Future agents may inspect repositories, crawl logs, reproduce defects, or propose requirements. Those capabilities must reduce uncertainty without silently converting hypotheses into accepted product behavior.

## Decision
Orchard distinguishes system workflows from delivery workflows.

- A system workflow governs clarification, investigation, splitting, and readiness inside Orchard.
- A delivery workflow governs repository changes and completion evidence after readiness.
- Future project workflows may refine system defaults only through explicit project authority.

The built-in Task policy resolves a `DEFINE_TASK` step. Its executor produces a definition assessment from captured intent, inspected context, outcome, scope, acceptance criteria, non-goals, and ambiguity analysis.

The built-in Bug policy resolves a `DEFINE_BUG` step with additional diagnostic, reproduction, expected-behavior, and regression-criterion obligations. Both use the generic workflow-step runtime defined by ADR 026 rather than owning separate orchestration code.

Each submission produces an immutable `WorkDefinitionManifest` containing:

- Global definition ID and per-work-item revision.
- Work-item identity and versioned system workflow.
- Requested outcome, current behavior, and required behavior.
- Scope, non-goals, and constraints.
- Acceptance criteria paired with explicit verification methods.
- Bug reproduction and regression criterion where applicable.
- Unresolved questions and proposed split titles.
- Deterministic assessment and manifest hash.

Definition records append to checksummed `work-definitions.jsonl` authority before entering the in-memory projection. The latest revision controls readiness. An earlier ready revision cannot override a later clarification or split decision.

Assessment outcomes are:

```text
NEEDS_INVESTIGATION
NEEDS_CLARIFICATION
NEEDS_SPLIT
READY
```

Missing observable facts route to investigation. Explicit unresolved questions route to clarification. Independent proposed outcomes route to splitting. Only a structurally complete definition with no unresolved questions becomes ready.

Only the latest `READY` manifest may enter delivery admission. The delivery run embeds that exact manifest. Definition revision closes once delivery starts, preventing its behavioral target from changing underneath evidence collection.

Every definition-backed delivery contract adds an `ACCEPTANCE` gate containing the pinned acceptance criteria and verification methods. This gate augments, rather than replaces, source-diff, build, test, and Bug regression evidence.

## Authority Boundary
Agents may produce observations and proposals during declared system-workflow phases. An observed stack trace, log line, command result, or failing test can carry machine-verifiable provenance. A proposed behavioral decision remains non-authoritative until accepted in a definition revision.

```text
Agent observation or proposal
-> System workflow assessment
-> Human or existing-contract acceptance
-> READY Work Definition
-> Delivery admission
```

An implementation agent may choose how to satisfy a Ready Work Definition. It may not decide what the requested behavior means.

## Consequences
- Task and Bug creation no longer implies delivery readiness.
- Ambiguity is visible and revisable instead of deferred into implementation.
- Acceptance criteria directly affect the evidence required for completion.
- Historical delivery runs retain the exact definition against which they were judged.
- Future investigation agents have bounded phases and cannot bypass readiness authority.
- Deterministic validation can prove explicit completeness but cannot discover every latent semantic ambiguity; requirement-gathering agents and human judgment remain necessary.
- Automatic child-item creation for split decisions remains future work.