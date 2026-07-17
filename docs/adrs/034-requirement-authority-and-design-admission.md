# ADR 034: Requirement Authority and Design Admission

## Status

Accepted

## Context

Orchard can define Task and Bug work, stage it in delivery circuits, and dispatch eligible nodes into immutable workflow contexts. Those controls do not by themselves prove that implementation work satisfies an admitted system design. A locally coherent Task can omit an Epic requirement, weaken a Story constraint, or begin against a child contract compiled from an obsolete parent revision.

Treating this as model hallucination would put authority in the wrong place. Human and model-authored designs are candidate claims. Orchard needs a durable requirement hierarchy, deterministic admission, an immutable acceptance contract, and a non-bypassable execution gate.

Existing Orchard projects predate this authority and must remain usable until their owners explicitly migrate them.

## Decision

Orchard introduces project-scoped design governance with five connected responsibilities.

### 9.0a Requirement Authority

Exact requirement authority follows the work-item hierarchy:

- Epic requirements are system requirements.
- Story requirements are subsystem requirements and trace to direct Epic requirement IDs.
- Task and Bug requirements are implementation requirements and trace to direct Story requirement IDs.

Requirement IDs are stable, normalized identifiers. Every non-Epic requirement names at least one direct-parent requirement. Every direct-parent requirement must be allocated to at least one child requirement before admission. Structural validation rejects absent, duplicate, invalid, or out-of-scope references.

This implementation proves explicit allocation and exact identity. It does not claim that structural comparison alone can establish semantic non-weakening. Feasibility, contradiction, policy conflict, and semantic weakening require additional admitted inspection authority.

### 9.0b Design Revision Registry

A Project becomes governed only through an explicit activation record. Activation is immutable and prospective, preserving compatibility for existing projects until migration.

Each design submission creates an immutable candidate revision for one Epic, Story, Task, or Bug. Revisions use optimistic concurrency through the preceding revision number and content hash. Candidate content includes problem, scope, assumptions, constraints, alternatives, architecture, failure modes, quality attributes, security and compliance impact, requirements, and acceptance criteria.

Activation, design revisions, admission decisions, findings, and acceptance contracts are stored as checksummed append-only JSONL events with monotonic IDs and forced writes. Restart recovery recomputes design, decision, and contract hashes; verifies hierarchy and activation; reruns deterministic inspection; and verifies exact parent references. A malformed recoverable tail is quarantined by the shared JSONL recovery policy, while semantic corruption fails startup.

### 9.0c Design Admission

Only the latest candidate revision for a work item may be decided. Deterministic inspection rejects missing design sections, missing or invalid parent traceability, unallocated parent requirements, absent acceptance paths, unknown gate types, and automated criteria without exact verification instructions.

A rejection is an immutable decision with structured findings. Correction creates a new candidate revision; it does not mutate the rejected record. A local model cannot admit its own output. The current API records human-authored candidates and human-triggered deterministic admission.

### 9.0d Acceptance-Contract Compilation

Admission and acceptance-contract compilation are one durable event. An admitted design cannot exist without its compiled contract.

The contract pins:

- the exact design ID, revision, and content hash;
- exact admitted parent design references;
- inherited parent requirement IDs; and
- criterion-level IDs, requirement traceability, descriptions, verification methods, and automated or human gates.

Contract identity is content hashed. Replay recompiles the contract from design authority and requires exact equality with the persisted contract.

### 9.0e Execution Enforcement

For an activated Project, Task or Bug execution fails closed unless its latest admitted design has a contract whose parent references exactly match the current admitted Story and Epic designs. Manual starts and automatic circuit dispatch use the same `WorkspaceStore.startWorkflow` gate.

A successful start embeds the complete acceptance contract in the immutable workflow context manifest and therefore in its manifest hash. Later parent design revisions do not mutate or invalidate an already-started run. They do make descendant contracts stale for future starts until each affected child is revised or readmitted against current parent authority.

Project governance views expose missing designs and stale descendant authority as blocking findings. Task and Bug designs cannot be revised after their delivery workflow has started.

## API

The workspace API exposes three authority transitions:

- `POST /api/projects/{projectId}/design-governance`
- `POST /api/designs`
- `POST /api/designs/{designId}/admission`

Workspace snapshots expose immutable design revision views, decisions, contracts, project activation, and current blocking findings. Rejected admission returns `422 Unprocessable Entity`; stale revisions and closed authority return `409 Conflict`; unavailable durable storage returns `503 Service Unavailable`.

## Consequences

- Requirement decomposition becomes explicit authority rather than prompt context.
- Candidate designs and rejected revisions remain auditable.
- Parent revisions cannot silently change the authority of future child execution.
- Workflow runs preserve the exact contract under which work began.
- Automatic dispatch cannot bypass design governance.
- Legacy projects retain their existing behavior until explicitly activated.
- Contract compilation and admission cannot fail independently or produce partial authority.

## Boundaries

- The current inspector enforces deterministic structural and traceability rules. Independent semantic design inspection, organization policy resolution, risk profiles, waivers, and named approval roles remain subsequent governance work.
- The external organizational Git policy source described by ADR 004 is not yet resolved or synchronized by this milestone.
- Criterion verification is pinned into the acceptance contract. ADR 035 compiles those criteria into the workflow evidence-gate engine.
- Governance transitions are available through the typed backend API and workspace projection. A dedicated Compose design editor and admission screen remain future UX work.
- Project activation has no deactivation path. Migration is an authority decision and applies prospectively.
- A parent revision reports stale descendants but does not automatically author replacement child designs.

## Alternatives Considered

### Trust the latest design text in context

Rejected because mutable text has no admission decision, stable identity, or proof that child execution uses the intended revision.

### Admit a design before compiling its contract

Rejected because a crash or compilation error could publish design authority that no executable acceptance contract represents.

### Recompute parent authority when evaluating an existing run

Rejected because later design changes would retroactively alter the contract under which work started.

### Enable governance for every existing Project immediately

Rejected because legacy projects have no design registry and would become unusable without an explicit migration decision.
