# ADR 024: Workflow Admission and Episodic Recall

## Status
Accepted

## Context
Starting engineering work requires more than moving a ticket to an In Progress column. Execution must know which repository revision, task hierarchy, workflow rules, evidence obligations, and prior engineering experience apply. Reconstructing previously solved failures wastes substantial time, while treating an old workaround as universal policy can reproduce obsolete mistakes.

Orchard therefore needs a durable admission boundary that recalls relevant precedent without allowing retrieved text, model output, or mutable repository state to become workflow authority.

## Decision
Only Tasks and Bugs may start execution workflows. Admission resolves their committed Story, Epic, and Project ancestry, requires an available bound repository with a clean committed `HEAD`, and captures that exact revision before any execution begins.

The resulting immutable context manifest contains the work-item hierarchy and content, repository path and revision, resolved workflow identity and version, and recalled work episodes. Its canonical serialized form is hashed. The complete manifest and resolved evidence contract are embedded in a checksummed workflow-run record so later changes cannot alter the meaning of historical admission.

The initial built-in workflows use these ordered phases:

```text
RECALL_CONTEXT -> EXECUTE -> VERIFY_EVIDENCE -> DECIDE_TRANSITION
```

Task completion requires source-diff, build, and test evidence. Bug completion additionally requires regression-test evidence. Resolving these requirements does not mark any gate satisfied.

Work episodes preserve concrete engineering history: problem, failed approaches, successful resolution, evidence summary, source revision, scope, and workflow identity. Initial recall is deterministic and restricted to the same Project, work-item type, and workflow. Candidates are ranked by normalized token overlap, capped at three, and copied into the context manifest. Derived indexes may later improve candidate discovery, but they cannot rewrite historical runs or determine episode authority.

Workflow runs are appended and flushed before entering the in-memory projection. A Task or Bug appears In Progress only because a durable active run exists; its entity status is not directly mutated. Admission failures leave both the workflow-run authority and board projection unchanged.

After admission, attempts, evidence, decisions, and cancellations are immutable events in a separate checksummed journal. An attempt preserves an approach, outcome, diagnostic hash, and whether it succeeded. An evidence record identifies its gate kind, repository revision, command, exit code, output hash, summary, and producer.

Evidence is accepted only for an open run and a valid Git revision descended from the pinned context revision. Source-diff evidence additionally requires a changed tree. Gate evaluation is deterministic: the evidence kind must satisfy a declared requirement, command evidence must match the requirement, the producer and hashes must be present, and the command must exit successfully. A later passing record for the same gate supersedes an earlier failure in the projection without deleting history.

Run and board state are projections of the immutable run plus ordered events:

```text
CONTEXT_READY -> EVIDENCE_PENDING -> EVIDENCE_BLOCKED
									-> DONE
CONTEXT_READY / EVIDENCE_PENDING / EVIDENCE_BLOCKED -> CANCELLED
```

`EVIDENCE_PENDING` means at least one gate is not yet satisfied. `EVIDENCE_BLOCKED` means the latest evidence for at least one gate failed. A run becomes `DONE` only when the latest evidence for every required gate passes and all accepted records target one resulting revision. The completion decision and generated work episode are appended atomically before the completed state is published.

The generated episode derives its problem and scope from pinned context, its failed approaches from attempt events, its resolution from passing source-diff evidence, and its evidence summary and source revision from accepted completion evidence. Cancellation closes a run but does not create a successful episode. A completed or cancelled run rejects further events.

On restart, Orchard validates event sequence continuity, run references, event ID monotonicity, legal terminal transitions, evidence references, and completion-episode linkage before reconstructing projections. The journals remain authority; in-memory run state and board columns are never persisted independently.

## Consequences
- Repository advancement after admission does not change the pinned context of an existing run.
- Recalled failed approaches can prevent repeated dead ends while retaining provenance and scope.
- Past fixes remain precedent rather than silently becoming approved policy.
- Dirty worktrees are rejected because `HEAD` alone would not describe the context visible to execution.
- Failed evidence and approaches remain available for diagnosis and future recall instead of being overwritten by a successful retry.
- Evidence producers can be agents, CI, or deterministic tools; Orchard records and evaluates their claims but does not execute commands from evidence payloads.
- Completion cannot be inferred from mutable repository state or a board-column write.
- Atomic decision-and-episode persistence prevents a completed run from existing without the memory it should teach future work.
- Cross-run supersession, review approval, and approved project-practice resolution remain future policy decisions.