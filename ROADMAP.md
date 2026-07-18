# Orchard Roadmap

## Roadmap Metadata

| Field | Value |
| --- | --- |
| Roadmap ID | `ORCHARD-ROADMAP` |
| Version | `1` |
| Status | `ACTIVE` |
| Updated | `2026-07-18` |
| Delivered baseline | Milestone 10.0 at commit `4f95ac5` |
| Next milestone | `10.1` Scoped Standards Overlays and Exception Authority |
| Canonical path | `ROADMAP.md` |

## How To Use This Roadmap

This file is Orchard's canonical statement of intended product direction. It is deliberately tracked at the repository root and treated as a foundation file by repository context collection so Architect, analysis, coding, audit, and conformance workflows can recover the plan in later sessions.

The roadmap is planning context, not execution authority. A milestone marked `NEXT` or `PLANNED` does not authorize repository mutation, bypass product genesis, admit a design, accept a plan, grant an exception, or change policy. Existing admitted runtime records and accepted ADRs remain authoritative for execution and architecture.

Authority precedence is:

1. Admitted runtime authority and revision-pinned evidence.
2. Accepted ADRs and non-bypassable integrity invariants.
3. This roadmap for sequence, intent, dependencies, and boundaries.
4. [User and developer documentation](docs/README.md) for current operation and implementation.
5. README summaries and conversational context.

Use these stable states:

- `COMPLETE`: delivered, validated, documented, committed, and included in the delivered baseline.
- `NEXT`: the single preferred milestone to design and implement next.
- `PLANNED`: dependency-ordered work with an agreed problem and outcome, but not yet admitted for implementation.
- `CANDIDATE`: valuable direction that still needs architectural discovery or sequencing.
- `DEFERRED`: intentionally postponed with a stated reason.
- `SUPERSEDED`: replaced by another milestone or decision; retain the entry and link its replacement.

## North Star

Orchard is a local-first autonomous software company that can understand a repository, turn intent and policy into bounded delivery work, execute through evidence-producing controls, verify promoted outcomes, learn from failure, and preserve enough authority and provenance to explain every decision later.

The product moat is not the number of agents. It is the closed institutional loop:

```text
Intent and policy
  -> revision-pinned repository understanding
  -> admitted design and work
  -> bounded implementation
  -> verification and independent audit
  -> local promotion
  -> follow-up evidence
  -> closure, remediation, or governed escalation
```

## Non-Negotiable Invariants

Every roadmap milestone must preserve these constraints:

- Model output is candidate data until deterministic validation and the required admission authority accept it.
- Repository evidence, standards, prompts, plans, and decisions are pinned to immutable hashes or revisions.
- Coding cannot approve itself; verification, independent audit, acceptance, and promotion remain separate authorities.
- A completed task is not proof of repository-level outcome or conformance.
- Historical authority is append-only or revisioned; recovery never rewrites prior truth.
- Explicit exceptions narrow policy for a declared scope and duration; they never erase inherited policy silently.
- Local-first is the default. Remote inference and remote control require explicit policy and attributable authority.
- Orchard does not push a remote repository unless a future accepted decision introduces that authority.

## Delivery Sequence

### Milestone 10.1: Scoped Standards Overlays and Exception Authority

- State: `NEXT`
- Depends on: `9.8`, `9.9`, `10.0`
- Governing ADRs: ADR 040, ADR 041, ADR 042; a new ADR is required before completion.

Goal: make effective engineering policy composable by scope and make `EXCEPTION_ACTIVE` a truthful, evidence-backed conformance disposition.

Why now: Milestone 10.0 can admit an `EXCEPTION_REQUEST`, and Milestone 9.8 recognizes active exceptions, but Orchard has no authority that can grant, scope, expire, revoke, or evaluate one.

Deliverables:

- Immutable organization, project, module, and work-item standard overlay records.
- A deterministic effective-standard resolver with explicit precedence and conflict reporting.
- Mandatory floors that child scopes may tighten but cannot weaken.
- Exception proposals and explicit admissions pinned to standard and repository revisions.
- Required exception scope, practice IDs, rationale, compensating controls, grantor, activation, expiry, review conditions, and revocation state.
- Routing from admitted Milestone 10.0 `EXCEPTION_REQUEST` decisions into candidate exception proposals without automatic grant.
- Conformance scans that emit `EXCEPTION_ACTIVE` only for an admitted, applicable, unexpired exception.
- Expiry, revocation, or scope drift that removes exception coverage and triggers new conformance attention.
- Cockpit projection of inherited policy, effective requirements, conflicts, exceptions, and expiry.

Exit evidence:

- Precedence and mandatory-floor tests cover every supported scope.
- Scope leakage, stale request, expiry, revocation, replay, and conflicting-overlay tests pass.
- A real scan proves that an applicable exception changes only its declared practices and scope.
- A follow-up scan proves that expired or revoked authority can no longer close a campaign.
- Full backend/frontend build, clean diagnostics, ADR, README update, and committed milestone.

Non-goals:

- Cryptographic identity, organizational login, quorum, and signatures.
- Fetching external policy repositories.
- Automatic legal or regulatory compliance claims.

### Milestone 10.2: Identity, Delegation, Quorum, and Signed Decisions

- State: `PLANNED`
- Depends on: `10.1`

Goal: replace caller-asserted actor strings with verifiable local authority for sensitive policy and acceptance decisions.

Deliverables:

- Durable human and service identities with local credential boundaries.
- Named roles, scoped delegation, expiry, and revocation.
- Policy-selectable quorum and segregation-of-duties rules.
- Signed admissions for standards, exceptions, promotions, and organizational policy changes.
- Historical verification that remains valid after role or delegation changes.
- Authentication and authorization on mutation APIs without exposing secrets to model context or persistence.

Exit evidence:

- Forged identity, expired delegation, insufficient quorum, self-approval, replay, and revoked signer tests fail closed.
- Existing local single-user projects have an explicit migration path.

Non-goals:

- General internet identity federation.
- Remote policy synchronization.

### Milestone 10.3: Verified Policy Sources and Deterministic Composition

- State: `PLANNED`
- Depends on: `10.1`, `10.2`

Goal: let organizations and communities publish reusable policy while preserving source identity, freshness, composition, and local inspectability.

Deliverables:

- Allowlisted Git policy sources with pinned revisions, manifests, signatures, and freshness limits.
- An open policy-pack format for standards, assurance packs, inspectors, and acceptance templates.
- Deterministic source synchronization and composition into effective scoped policy.
- Onboarding attestations for mandatory sources.
- Impact analysis when a source revision changes, with migration, re-attestation, remediation, or suspension decisions.
- Source-bound disposable RAG indexes that cannot become policy authority.

Exit evidence:

- Stale, missing, unsigned, conflicting, and unauthorized sources fail closed.
- In-flight work retains its pinned policy while new work adopts an admitted source revision.

Non-goals:

- Treating retrieval output as policy.
- Silent policy updates from arbitrary public repositories.

### Milestone 10.4: Resolution Action Executors and Policy Migration

- State: `PLANNED`
- Depends on: `10.1`, `10.3`

Goal: execute admitted non-delivery resolution decisions through specialized authorities instead of leaving them as durable requests.

Deliverables:

- `RESCAN` decisions schedule one revision-pinned conformance scan idempotently.
- `STANDARD_CLARIFICATION` decisions produce candidate successor standard revisions with impact analysis and explicit admission.
- Exception decisions link to the scoped exception authority from Milestone 10.1.
- Policy changes identify affected campaigns, work definitions, designs, and in-flight runs.
- Migration decisions preserve historical acceptance context and never retroactively authorize completed work.
- Revisioned supersession for previously admitted resolution decisions.

Exit evidence:

- Duplicate scheduling, stale decisions, policy drift, supersession, and restart recovery tests pass.
- No non-delivery resolution action can mutate its target authority directly.

## Product Evolution Arc

### Milestone 11.0: Conversational Product Successor Revisions

- State: `PLANNED`
- Depends on: `10.4`

Goal: let an admitted product evolve through a conversational successor revision with deterministic downstream invalidation.

Deliverables:

- Candidate genesis successor revisions pinned to the current admitted revision.
- Impact analysis across experience, architecture, blueprint, designs, plans, campaigns, and repository state.
- Explicit preserve, supersede, migrate, cancel, or re-attest decisions for affected authority.
- No silent mutation of in-flight work.

### Milestone 11.1: Repository ADR Export and Correlation

- State: `PLANNED`
- Depends on: `11.0`

Goal: export admitted architectural decisions into repository Markdown while retaining Orchard identity and correlation.

Deliverables:

- Deterministic ADR rendering with stable Orchard authority IDs and hashes.
- Safe updates through successor ADRs rather than rewriting historical decisions.
- Bidirectional correlation among repository ADRs, designs, implementation evidence, and conformance findings.

### Milestone 11.2: Portfolio and Roadmap Governance

- State: `CANDIDATE`
- Depends on: `11.0`

Goal: evolve this repository roadmap from retrieval context into admitted portfolio authority with priorities, capacity, dependencies, and outcome review.

Candidate scope:

- Parse a versioned roadmap schema into candidate portfolio records.
- Require explicit admission before roadmap items create product or delivery work.
- Record milestone forecasts versus actual evidence without treating estimates as guarantees.
- Update roadmap status from promoted evidence rather than model assertion.

## Repository Intelligence Arc

### Milestone 12.0: Symbol-Aware Repository Evidence Graph

- State: `PLANNED`
- Depends on: `11.1`

Goal: replace bounded lexical file selection as the primary navigation mechanism with source-aware, revision-pinned evidence relationships.

Deliverables:

- Language-server or parser-backed symbols, definitions, references, modules, tests, and configuration relationships.
- Content-addressed evidence nodes tied to exact repository revisions.
- Deterministic fallback for unsupported languages.
- Retrieval explanations showing why every file or symbol entered model context.

### Milestone 12.1: Source-Bound Semantic Retrieval

- State: `PLANNED`
- Depends on: `12.0`

Goal: add semantic recall without allowing embeddings or generated summaries to replace source authority.

Deliverables:

- Disposable vector indexes keyed by repository revision and source content hash.
- Hybrid lexical, symbol, and semantic ranking.
- Citation verification against original tracked files before model use or admission.
- Index invalidation and rebuild after repository or policy revision changes.

### Milestone 12.2: Evidence-Derived Organizational Learning

- State: `CANDIDATE`
- Depends on: `12.1`, `10.3`

Goal: derive reusable practices from accepted outcomes while keeping learned guidance separate from policy until admitted.

Candidate scope:

- Distill recurring accepted patterns, failures, and review corrections into candidate practices.
- Track provenance from every candidate lesson to completed episodes and evidence.
- Require explicit standards or policy-pack admission before learned guidance becomes enforceable.

## Operational Scale Arc

### Milestone 13.0: Storage Lifecycle and Workspace Capacity

- State: `PLANNED`
- Depends on: `11.0`

Goal: remove the current 32-entity product limit without weakening replay, inspectability, or recovery.

Deliverables:

- Governed archival and retention for worktrees, branches, evidence, model executions, and completed workspace entities.
- Capacity-safe pagination or partitioning of active workspace authority.
- Rehydration and audit access to archived records.
- Storage budgets and deterministic cleanup policy.

### Milestone 13.1: Cost, Latency, and Resource Accountability

- State: `PLANNED`
- Depends on: `13.0`

Goal: make campaign and company resource consumption first-class evidence for planning and governance.

Deliverables:

- Per-model, per-role, per-campaign token, latency, memory, and optional monetary accounting.
- User-delegated budgets and fail-closed admission when exhausted.
- Outcome-linked efficiency metrics that do not reward bypassing quality gates.

### Milestone 13.2: Multi-Client and Remote Architect Control

- State: `CANDIDATE`
- Depends on: `10.2`, `13.0`

Goal: support multiple authenticated clients and remote control without losing single-writer authority or local-first custody.

Candidate scope:

- Concurrency control, leases, and conflict projections for multiple clients.
- Authenticated remote Architect protocol.
- Explicit policy for remote model execution, repository mutation, and optional remote Git publication.

## Deferred Directions

These directions are intentionally not scheduled:

- Automatic remote Git push: deferred until identity, review, and remote publication authority exist.
- Autonomous legal or regulatory compliance claims: Orchard may enforce technical controls but cannot manufacture legal authority.
- Silent model self-training from private repositories: deferred until provenance, consent, evaluation, rollback, and model-governance boundaries are designed.
- Unbounded autonomous retries: rejected in favor of evidence-backed resolution and explicit resource policy.
- Distributed agent swarms: deferred until repository understanding, authority composition, and operational accountability justify the coordination cost.

## Completed Baseline

The README contains the detailed delivered history. The current architectural line is:

| Milestone | Outcome | State |
| --- | --- | --- |
| 9.4 | Guided Product Genesis | `COMPLETE` |
| 9.5 | Local Autonomous Company | `COMPLETE` |
| 9.6 | Repository Analysis and Execution-Plan Compilation | `COMPLETE` |
| 9.7 | Provider-Neutral Model Runtime | `COMPLETE` |
| 9.8 | Engineering Standards and Conformance Compiler | `COMPLETE` |
| 9.9 | Closed-Loop Conformance Remediation | `COMPLETE` |
| 10.0 | Campaign Resolution and Successor Governance | `COMPLETE` |

Earlier milestones established the workspace, deterministic workflows, durable evidence, work definitions, staged circuits, requirement authority, contract-compiled gates, governed coding, toolchain policy, and product/company foundations required by this line.

## Roadmap Update Protocol

Update this file in the same change that alters roadmap intent.

1. Keep exactly one milestone in state `NEXT`.
2. Update `Version`, `Updated`, `Delivered baseline`, and `Next milestone` when the delivered baseline changes.
3. Mark a milestone `COMPLETE` only after implementation, focused tests, full build, documentation, and commit are complete.
4. Add or update an ADR when sequencing introduces a new authority boundary or changes an accepted architectural decision.
5. Move displaced work to `DEFERRED` or `SUPERSEDED`; do not delete it and erase the decision trail.
6. Keep milestone IDs stable. If scope changes materially, explain the change in the roadmap log.
7. Keep README project status concise and link here for future direction.
8. Treat Git history as the immutable record of prior roadmap versions; the log below summarizes meaningful planning changes.

## Roadmap Log

| Date | Version | Change |
| --- | --- | --- |
| 2026-07-18 | 1 | Established the canonical roadmap after Milestone 10.0; selected scoped standards overlays and exception authority as Milestone 10.1. |
