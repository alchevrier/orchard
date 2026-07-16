# ADR 026: Generic Workflow Step Runtime

## Status
Accepted

## Context
System definition and delivery workflows initially represented their phases independently. Workspace code separately decided start eligibility, context composition, evidence transitions, completion, and cancellation. Adding investigation, review, requirement gathering, reproduction, implementation, and project-specific workflows in that form would multiply special-case state machines.

The common behavior is stable across those domains:

```text
START CONDITION
-> RECALL CONTEXT
-> EXECUTE
-> PRODUCE EVIDENCE
-> SIGNAL TRANSITION
```

Executors may differ, but execution mechanism must not own workflow authority.

## Decision
Orchard represents each executable unit as a versioned `WorkflowStepDefinition` containing:

- A start condition expressed as required authoritative facts.
- A context contract listing the inputs that must be pinned before execution.
- An execution contract listing allowed executor classes.
- An evidence contract defining the outputs required from execution.
- Typed transition signals mapping execution outcomes to the next step or terminal state.

The generic engine performs two deterministic operations:

```text
canStart(step, availableFacts)
resolveSignal(step, emittedSignal)
```

Context completeness is checked separately from start eligibility. A start condition may inspect current authority to determine whether execution can begin. Context recall then freezes the exact ticket, system policy, work definition, repository revision, and historical episodes visible to the executor.

Current executor classes are:

```text
HUMAN
AGENT
DETERMINISTIC_TOOL
```

These are capabilities declared by policy. An executor cannot change the context contract, evidence contract, or available transition signals.

The initial policies use one step each:

- `DEFINE_TASK` and `DEFINE_BUG` produce assessed Work Definition evidence and signal investigation, clarification, splitting, or readiness.
- `DELIVER_CHANGE` consumes a Ready Work Definition and pinned repository context, then signals evidence progress, evidence rejection, completion, or cancellation.

Every delivery evidence event now records its signal. A passing partial gate signals `EVIDENCE_ACCEPTED -> EVIDENCE_PENDING`; a failed gate signals `EVIDENCE_REJECTED -> EVIDENCE_BLOCKED`; a passing retry can therefore unblock the run through declared policy. Completion and cancellation likewise record `COMPLETED` and `CANCELLED` signals.

Persisted pre-runtime workflow summaries remain readable. New workflow records carry typed step definitions, and recovery verifies that durable signal names, targets, and acceptance decisions match the pinned policy.

## Consequences
- System, delivery, investigation, review, and future project workflows can share one runtime.
- Agents become replaceable step executors rather than owners of process or transition policy.
- Every step has an explicit answer for when it starts, what it knows, who may execute it, what it must prove, and what it may signal.
- Transition history records policy-level signals instead of requiring state inference from incidental events.
- Workflow policies remain versioned Kotlin data for now; loading approved project policies from durable configuration is future work.
- Multi-step scheduling is not yet required by the initial one-step policies, but the signal target contract provides the boundary for adding it without another domain-specific state machine.