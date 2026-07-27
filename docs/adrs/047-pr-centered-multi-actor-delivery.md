# ADR 047: PR-Centered Multi-Actor Delivery and Corrective Lineage

## Status

Proposed

Extends ADR 004 and ADR 046. It preserves their immutable evidence, admission, bounded coding, independent acceptance, and controlled-promotion boundaries.

## Context

ADR 046 established adequate executable work packages, bounded coding tools, persistent candidates, and local candidate PR artifacts. It does not yet make the candidate PR the complete unit of review and correction. In particular, repository evidence, implementation ownership, allowed operations, review findings, and design invalidation are represented or reconciled across overlapping phases.

That overlap makes incomplete plans appear plausible until a later validator rejects an omitted owner, operation, or scope relation. Replanning then risks discarding a useful candidate instead of preserving it as evidence and deriving the smallest correction. A technically correct implementation can also fail to meet the admitted user intent, while a local implementation failure can reveal a missing or contradictory design decision rather than a coding defect.

Orchard must support both constrained machines and larger local systems. It therefore needs independently schedulable stages, but it must not turn routine factual repository work into broad model calls. A durable conversation can make the work understandable to an operator and let specialized actors share findings, but conversation text cannot replace governed authority.

## Decision

### Candidate PR is the correction unit

Every coding execution produces or updates one immutable local candidate PR lineage. A candidate PR records its parent candidate when it is corrective and pins:

- the admitted design revision and compiled work-package revision;
- the base and candidate repository revisions;
- actual changed paths, diff, implementation claims, checks, observations, and declared deviations;
- structured code-review, intent-review, design-review, and integration-review findings; and
- its terminal disposition: `REVIEW_REQUIRED`, `REPAIR_REQUIRED`, `ACCEPTED`, `BLOCKED`, `SUPERSEDED`, or `ABANDONED`.

A rejected candidate remains accepted as historical evidence of an attempted solution, but is never accepted for promotion. A correction compiles a bounded successor from unresolved findings and the pinned candidate state. Orchard does not restart repository analysis for ordinary compile, test, or review repairs.

### Authority is compiled before coding

The implementation assignment distinguishes three artifacts that may refer to the same paths but have different meanings:

- **Evidence authority** records repository observations and the exact sources that support them.
- **Ownership authority** records the production owners responsible for requested behavior, including explicit read-only compliant evidence where behavior need not change.
- **Operation authority** records the paths and actions a coding candidate may perform.

The deterministic compiler derives the executable work package from these artifacts, the admitted design, and the acceptance contract. A model may propose design or implementation instructions, but it cannot silently turn evidence into ownership, ownership into an operation, or an operation into proof of intent satisfaction.

### Independent review authorities

Review findings have a typed target and cannot be collapsed into one generic approval.

- **Code review** assesses technical correctness, safety, maintainability, compatibility, and test adequacy for the actual candidate diff.
- **Intent review** assesses whether observable behavior satisfies admitted outcome, requirements, constraints, non-goals, and acceptance criteria.
- **Design review** assesses whether the admitted technical solution is coherent, feasible, internally consistent, and sufficient for the requested behavior before broad implementation; later findings may reopen it.
- **Integration review** assesses a self-contained executable scenario against public interfaces and durable behavior.

Each finding identifies its source authority, affected requirement or invariant, candidate revision, observed evidence, severity, and correction target: candidate repair, work-package recompilation, design revision, clarification, or escalation.

Each correction target has an independent durable dispatcher. Clarification and escalation block the candidate and await an explicit response; they never degrade into a coding retry. Bounded automated reviewers publish only typed review findings through the review ledger and hold no admission, acceptance, or promotion authority.

### Design changes reconcile downstream work

Design, work-package, candidate, review, and acceptance artifacts form a revision-pinned dependency graph:

```text
design revision -> work package -> candidate PR -> review findings -> acceptance
```

When an admitted design revision changes, deterministic impact analysis identifies dependent work packages and candidates. Affected records remain immutable but become `SUPERSEDED` or `REQUIRES_RECONCILIATION`; they cannot promote under obsolete design authority. Orchard recompiles only the affected downstream authority after explicit successor admission.

The invalidation record pins both design references. It triggers a successor repository-analysis revision explicitly pinned to the admitted successor design; normal package compilation then derives a new executable package from that latest plan. The prior plan, package, candidate, and invalidation evidence remain immutable.

### Self-contained integration execution

Integration evidence starts an isolated application runtime using temporary persistence, ephemeral ports, deterministic clocks and model/provider boundaries, and a disposable repository fixture. It exercises public APIs and typed clients, survives restart where required, and performs clean shutdown. It must not require an already running Orchard process, existing local journals, user-specific configuration, or a live model service.

The production model boundary remains separately tested. A deterministic integration provider proves Orchard behavior without treating model availability as prerequisite for every integration scenario.

### Actor conversation and learning

Specialized actors may publish bounded, role-specific findings into a canonical conversation associated with the design, work package, or candidate PR. Conversation messages are readable projections; their referenced hashes and structured findings are the authority.

Project learning retains accepted decisions, rejected alternatives, recurring findings, model/profile performance, and verified repository conventions as revision-pinned episodic evidence. Policy, design, acceptance, and routing changes require their own governed revisions; persuasive discussion does not self-modify authority.

The initial candidate-outcome learning ledger derives terminal episodes from candidate disposition authority and pins candidate, package, review, correction, and disposition hashes. It is retrieval-only and cannot alter policy, design, acceptance, routing, or promotion.

### Resource-aware pipeline

Deterministic collection, compilation, validation, and reconciliation stages run without a model when possible. Model-backed design, review, and coding stages receive only the bounded authority required for their role. Independent work items may pipeline on capable hardware; constrained systems serialize model admission while retaining durable intermediate artifacts and resuming at the first invalid stage.

## Consequences

- Orchard converges around imperfect concrete candidates instead of repeatedly discarding them after late validation.
- A review failure can be routed to the smallest correct authority boundary rather than always returning to coding or broad analysis.
- Coding, intent, and design reviewers receive distinct evidence packages and cannot silently substitute one judgment for another.
- Design corrections invalidate only dependent downstream work and preserve the historical reasoning that led to each candidate.
- Integration evidence becomes reproducible from a clean local environment.
- Pipeline scheduling can improve throughput on larger hardware and reduce peak model pressure on constrained hardware.
- The implementation introduces new durable schemas, compatibility rules, dependency reconciliation, test harnesses, and UI projections; it must be delivered in staged, replay-tested slices.

## Boundaries

- This decision does not allow a model or reviewer to admit its own design, accept its own candidate, alter policy, or promote code.
- A conversation is not an approval channel unless it contains an exact separately authorized admission.
- Retaining rejected candidates does not permit promotion of an invalid candidate or mutation of prior evidence.
- Self-contained integration mode does not replace production-provider conformance testing.
- The first implementation may reuse existing work-package and candidate-PR records through backward-compatible successor fields before introducing a broader authority-graph store.