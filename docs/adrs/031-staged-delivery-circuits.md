# ADR 031: Staged Delivery Circuits

## Status

Accepted

## Context

Resource-aware admission can safely run independent model work, but machine capacity alone does not establish which work is logically independent. A Story may require an API contract before frontend and backend implementation can fan out, and an Epic may require one Story's accepted outputs before another begins.

Leaving that ordering inside an agent prompt makes model memory responsible for execution correctness. Orchard needs durable, inspectable circuit authority before it can automatically dispatch parallel work.

## Decision

Orchard accepts a versioned staged delivery plan for an Epic or Story. A Story plan covers every direct Task and Bug. An Epic plan covers every direct Story. Each accepted plan is an ordered set of stages containing nodes, dependency wires, typed input requirements, typed output declarations, and a pinned stage execution workflow.

The dependency graph is authoritative. Human-readable labels such as `1a`, `1b`, and `2a` are deterministic projections of stage and node order. Dependencies must point strictly to an earlier stage, which makes cycles and same-stage ordering impossible by construction.

Stage execution workflows are resolved from Orchard's versioned registry. They govern stage entry and exit policy; they are distinct from the Task or Bug delivery workflow that governs an individual workflow run. Contract stages require accepted outputs before exit, integration stages wait for all prior outputs, sequential stages wait for all prior nodes, and parallel stages follow their explicit dependency and artifact wires. Unknown IDs or versions are rejected.

A node becomes eligible only when:

1. every dependency node is complete through its governed workflow
2. every declared consumed artifact has an accepted instance from the named producer
3. the pinned stage workflow's entry policy is satisfied

Story-plan artifact instances are deterministic projections of durable completed-run evidence. Each output declaration explicitly names a delivery evidence kind. Orchard publishes the instance only when the exact evidence ID occurs in the producer's accepted `DONE` decision, binding the artifact kind and name to its producer node, work item, workflow run, repository revision, and output hash. A declaration alone never opens a downstream gate.

Accepted plans are append-only JSONL authority with monotonic plan IDs, per-scope revisions, checksummed envelopes, file locking, and forced writes. Revisions require the active plan's revision and hash, preventing stale editors from silently replacing circuit authority. The plan hash binds content and acceptance provenance. Historical revisions recover structurally; current hierarchy coverage is checked only against the latest revision.

A plan is immutable after a non-cancelled node starts. Cancelling a node releases that execution attempt and permits an explicit replacement run. Completion remains evidence-derived.

## Consequences

- Logical dependency authority exists before automatic parallel dispatch.
- Independent nodes in one stage can be admitted concurrently under the machine resource controller.
- API contracts and other typed outputs become inspectable circuit signals rather than prompt conventions.
- Concurrent editors using the authoritative backend receive a conflict instead of silently overwriting an accepted plan.
- Hierarchy growth can be covered by a new revision without making earlier authority unrecoverable.
- The desktop planner selects only registered stage workflows and displays derived node eligibility.

## Boundaries

- Plans are manually constructed and accepted in this milestone. Architect decomposition and automatic plan materialization remain future work.
- Dispatch remains explicit. Orchard does not yet maintain a durable ready queue or automatically start every eligible node.
- Stage workflow definitions currently use built-in deterministic entry and exit policies; user-authored workflow installation is not yet supported.
- Artifact bytes remain in their producing system or repository. Orchard stores the evidence-bound artifact identity, not an arbitrary payload blob.
- Epic-level artifact wires are rejected until Orchard has an explicit policy for aggregating Story outputs. Epic circuits currently use dependency completion signals only.
- Task and Bug runs continue to use their governed delivery workflows. A stage workflow orchestrates the circuit and does not replace those run contracts.
- Plan revisions are disallowed after execution starts except through cancellation and explicit retry; general execution supersession is future work.
- One Orchard backend process owns a workspace authority directory. The file lock protects append integrity, but cross-process scope-level stale-conflict classification is not supported.

## Alternatives Considered

### Encode stages in agent prompts

Rejected because ordering would depend on model attention and could not be validated, recovered, or safely dispatched.

### Treat stage numbers as authority

Rejected because numbers describe presentation but do not identify exact dependency edges or typed signals.

### Unlock consumers when producers are marked done

Rejected because completion does not prove that a declared contract or other required output exists.

### Allow arbitrary workflow identifiers

Rejected because an opaque string cannot govern runtime behavior. Accepted workflow pins must resolve to executable deterministic policy.

### Automatically dispatch as soon as the graph exists

Rejected for this milestone because durable queueing, worktree isolation, retry policy, and integration ownership need separate authority.
