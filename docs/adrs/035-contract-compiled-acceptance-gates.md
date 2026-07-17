# ADR 035: Contract-Compiled Acceptance Gates

## Status

Accepted

## Context

Milestone 9.0 makes admitted Epic, Story, and Task or Bug designs execution authority. A governed workflow cannot start without a current implementation acceptance contract, and the immutable run context pins that exact contract.

Before this decision, workflow completion still depended only on the built-in delivery evidence contract and the accepted Work Definition. The admitted design criteria were visible in context but did not participate in the completion decision. A run could therefore begin under exact requirement authority and later complete without satisfying every criterion that authorized it.

This gap also prevents reusable organizational or community policy packs from becoming enforceable. A verified policy source is useful authority only when its compiled criteria can deterministically block or permit a workflow transition.

## Decision

Orchard compiles every criterion from a pinned acceptance contract into the immutable resolved delivery workflow at start time.

Each compiled criterion gate records:

- the stable criterion ID and requirement ID;
- a deterministic evidence kind derived from the criterion ID;
- the admitted description and verification instruction;
- whether the gate is `AUTOMATED` or `HUMAN`; and
- the acceptance contract and design authority already pinned by the run context.

The resolved workflow and evidence contract advance to version 3 for governed runs. Legacy and non-governed runs retain their existing resolved workflows and completion behavior.

### Automated Criteria

An automated criterion is satisfied only by passing evidence for its deterministic criterion kind at the target repository revision. The submitted command must exactly equal the verification instruction admitted into the acceptance contract. Evidence for another kind, a failed command, or an approximate verification instruction cannot satisfy the criterion.

### Human Criteria

A human criterion cannot be represented by command evidence. It requires an immutable `CriterionJudgment` recording:

- criterion and requirement identity;
- exact repository revision;
- pinned acceptance-contract hash;
- named approver;
- rationale;
- approval or rejection; and
- record time and monotonic workflow event identity.

A later judgment for the same criterion and revision supersedes the earlier judgment in the derived gate view without erasing history. A rejection blocks the workflow. Approval satisfies only that exact criterion at that exact revision.

The current API records the approver identity claimed by the caller. Authentication, role resolution, quorum, and segregation of duties remain separate policy-authority work and must be added before a policy pack can require cryptographically verified organizational approval.

### Revision-Scoped Completion

Completion is derived from one resulting repository revision. Every built-in delivery gate, Work Definition acceptance gate, automated design criterion, and human design criterion must be satisfied at that same revision. Evidence or judgments from another revision do not compose into completion.

The completion decision records all evidence IDs and human judgment IDs used. The generated work episode includes both deterministic evidence summaries and accepted human rationales.

### Replay and Projection

Workflow replay recompiles the expected resolved workflow from the pinned Work Definition and acceptance contract. It rejects events with:

- evidence outside the resolved contract;
- command evidence for a human gate;
- an automated criterion command that differs from admitted verification;
- a judgment for the wrong criterion, requirement, contract, or malformed authority fields;
- transition references to absent evidence or judgments; or
- a completion claim whose exact revision-scoped gate set is not satisfied.

Replay revalidates every evidence and judgment revision against the bound Git repository, requires the canonical descendant commit, and recomputes whether evidence passed from repository change state or command exit status. A syntactically valid hash or persisted `passed` flag is not sufficient authority.

Workflow run, event, and episode journals recover a valid prefix when the final JSONL record is malformed or checksum-invalid, atomically quarantine the rejected suffix, and then apply semantic replay validation. Corruption with later nonblank records is interior corruption and fails startup.

Workspace run views expose immutable judgment history and derived criterion gate status as `PENDING`, `REJECTED`, or `PASSED`, including the target revision and authority event ID.

The workspace API accepts human judgments at:

- `POST /api/workflow-runs/{runId}/criterion-judgments`

## Consequences

- Admitted design criteria now govern both workflow admission and workflow completion.
- A coding agent cannot replace a human judgment with generated command evidence.
- An automated check cannot silently substitute a weaker command for admitted verification.
- Historical runs remain bound to the criteria and workflow version under which they started.
- Criterion-level blocking is inspectable through typed backend and desktop network projections.
- Verified organization and community policy packs now have an enforcement target: applicable rules can compile into the same acceptance-gate model.

## Boundaries

- Orchard's governed coding worker now executes admitted verification through a bounded process adapter. OS-level filesystem and network sandboxing remains a boundary documented by ADR 036.
- Recovering workflow evidence currently requires the bound Git repository and referenced commits to be available so Orchard can re-prove revision ancestry and outcomes. Missing replay dependencies fail startup rather than accepting unverifiable completion history; durable source mirroring is future work.
- Approver names are attributable claims but are not yet authenticated identities or role-authority proofs.
- Human quorum, delegated authority, time-bounded waivers, and segregation of duties remain future policy-composition work.
- Verification instructions are exact strings. A future deterministic runner profile may compile richer typed command, environment, timeout, and output contracts.
- A dedicated Compose judgment control is not included; the typed API and projection are available for integrations and subsequent UI work.
- External Git policy synchronization, policy-pack manifests, signature verification, composition, and source-bound RAG remain the next governance arc.

## Alternatives Considered

### Keep admitted criteria as context only

Rejected because visible context does not constrain completion and therefore is not enforceable authority.

### Convert every human criterion into an `ACCEPTANCE` evidence command

Rejected because command evidence cannot represent accountable human judgment and would let an agent self-certify subjective or delegated decisions.

### Evaluate criteria against the latest available revision independently

Rejected because evidence from different candidate revisions could combine into a completion claim that no single artifact revision satisfies.

### Resolve policy criteria dynamically at completion time

Rejected because later policy or design changes would retroactively alter an in-flight run. Criteria are compiled and pinned when the run starts.
