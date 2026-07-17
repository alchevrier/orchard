# ADR 033: Durable Circuit Dispatch

## Status

Accepted

## Context

Staged delivery plans make dependency, stage, and artifact gates authoritative, and Architect synthesis can propose those plans without accepting them. Milestone 8.1 still requires a user to start every eligible Task or Bug. Eligibility exists only as a derived view, so a backend restart or temporarily unavailable repository has no durable record that work is waiting to start.

Parallel starts also cannot safely share the bound repository worktree. A coding attempt needs an isolated repository revision and ownership identity before an execution worker can be introduced. Integration stages need one named owner rather than several agents racing to combine outputs.

## Decision

Orchard materializes each eligible Task or Bug node as an immutable `CircuitDispatch` before creating its workflow run. Dispatches are append-only checksummed JSONL authority with monotonic IDs, forced writes, restart recovery, and hashes binding the exact plan ID, revision, plan hash, scope, stage, node, work item, priority, integration role, and creation time.

Eligibility remains derived from the accepted circuit, current hierarchy, READY Work Definition, dependency completion, accepted artifact instances, and stage entry policy. Reconciliation is idempotent: the same plan node receives at most one initial dispatch. Dispatch priority is deterministic from stage and node order, and pending records advance in priority and dispatch-ID order.

A production scheduler retries pending dispatches once per second. Plan acceptance, READY Work Definition acceptance, repository binding, accepted workflow completion, and backend recovery also trigger reconciliation. Temporary repository unavailability or a dirty bound worktree leaves the dispatch pending rather than discarding or falsely completing it.

Starting a dispatch creates the existing governed delivery workflow run. Its immutable context manifest pins the dispatch ID and a workspace reservation in addition to the work item, workflow, repository revision, and recalled episodes. The dispatch record remains immutable; `PENDING`, `RUNNING`, `DONE`, and `CANCELLED` are derived views from the linked workflow run.

For file-backed repository bindings, Orchard creates a deterministic Git worktree and branch named for the dispatch under the workspace authority directory. The worktree must be clean and at the exact admitted base revision before the run is published. Independent dispatches therefore receive distinct filesystem and branch ownership.

An `integration-v1` stage must contain exactly one node. Its dispatch is marked as the integration owner and receives a dedicated integration-mode worktree reservation. Other stage workflows receive isolated-mode reservations. Component completion and artifact gates still control when the integration dispatch becomes eligible.

Cancellation is terminal for one execution attempt and does not trigger an automatic restart. An explicit start request after cancellation appends a replacement dispatch with a new dispatch ID, branch, worktree, and workflow run. Prior dispatches and runs remain immutable.

The workspace snapshot exposes typed dispatch authority, derived state, linked run ID, priority, and integration ownership. The desktop circuit lanes display queue and ownership state, workflow cards display their pinned reservation, and circuit-managed tickets no longer offer a redundant initial manual-start action.

## Consequences

- Accepted circuits automatically create governed workflow runs when their node gates and repository admission pass.
- Dependency and accepted-artifact transitions fan out newly eligible nodes without model judgment or user bookkeeping.
- Pending work survives restart and transient admission failure without duplicate starts.
- Every automatically started run is traceable to exact plan and dispatch authority.
- Parallel nodes no longer share the mutable bound repository worktree.
- Integration ownership is structurally singular and inspectable.
- Explicit cancellation and replacement preserve attempt history and allocate a fresh workspace.
- Dispatch journal corruption, invalid plan references, node mismatches, or hash mismatches fail startup rather than being ignored.

## Boundaries

- Dispatch starts a governed workflow context; Orchard does not yet launch a coding-agent process, grant it filesystem tools, or consume its output automatically. That requires a separately governed execution-worker boundary.
- Integration ownership reserves the integration worktree but does not merge, rebase, resolve conflicts, or publish branches. Those operations require explicit integration policy and evidence.
- Worktrees and branches are retained for audit and retry isolation. Automated retention, archival, and cleanup policy is future work.
- Priorities are deterministic circuit order, not user-configurable scheduling classes or deadlines.
- Model resource leases govern local inference. Orchard does not yet reserve CPU, memory, or accelerator capacity for future coding-agent processes.
- Epic circuits continue to gate Story completion. Task and Bug dispatch is materialized from accepted Story circuits; Story-output aggregation remains undefined.
- One Orchard backend process owns a workspace authority directory. The dispatch scheduler is not a distributed queue and does not provide cross-process claiming.
- A plan can become durable immediately before dispatch append fails. Recovery reconciles the missing dispatch from accepted plan authority; it never rolls back or fabricates the accepted plan.

## Alternatives Considered

### Derive pending work only from each snapshot

Rejected because a view is not durable intent, cannot preserve queue identity, and provides no provenance for an automatically created run.

### Start workflows before recording dispatch authority

Rejected because a crash could create work with no durable explanation of which plan node authorized it.

### Let parallel runs use the bound repository directly

Rejected because one dirty checkout cannot provide isolated ownership or trustworthy per-attempt revision evidence.

### Automatically retry cancelled work

Rejected because cancellation is an explicit authority decision. Retrying it without a new command would negate that decision and could loop indefinitely.

### Allow several integration nodes in one stage

Rejected because integration order and repository ownership would become a race rather than accepted circuit authority.
