# Orchard Roadmap

## Roadmap Metadata

| Field | Value |
| --- | --- |
| Roadmap ID | `ORCHARD-ROADMAP` |
| Version | `3` |
| Status | `ACTIVE` |
| Updated | `2026-07-18` |
| Delivered baseline | Milestone 10.1 |
| Next milestone | `10.2` Durable Multi-Objective Conversational Conductor |
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
- `IN_PROGRESS`: implementation is admitted and underway, but one or more exit-evidence gates remain open.
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

- State: `COMPLETE`
- Depends on: `9.8`, `9.9`, `10.0`
- Governing ADRs: ADR 040, ADR 041, ADR 042, ADR 043.

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

### Milestone 10.2: Durable Multi-Objective Conversational Conductor

- State: `IN_PROGRESS`
- Depends on: `9.4`, `9.5`, `9.6`, `9.7`, `10.1`
- Governing ADRs: ADR 010, ADR 011, ADR 038 through ADR 044; ADR 044 must be accepted before completion.

Goal: make one durable conversation the primary control surface for several concurrent engineering objectives, from discussion and investigation through admitted planning, coding, verification, independent audit, acceptance, and local promotion.

Why now: Orchard already owns the individual company authorities, but its Architect chat is request-local, globally serialized, create-only, and disconnected from downstream lifecycle records. The shortest path to replacing the current coding-agent workflow is to conduct those existing authorities conversationally rather than add another isolated governance layer.

Product contract:

- One chronological conversation may contain several explicitly identified objective lanes.
- The operator can discuss, investigate, propose, admit, start, pause, resume, reprioritize, redirect, cancel, and inspect work without leaving the conversation.
- Conversation records prove what was said and correlated; existing domain records remain authoritative for product, work, code, evidence, audit, policy, and promotion.
- Model interpretation remains candidate data. Read-only discussion and inspection may run directly, while mutation requires an exact valid user admission.
- The user experiences one long-lived chat, but every model call receives a bounded reconstruction rather than an unbounded transcript.
- Independent objectives may progress concurrently under resource and repository policy; commands within one objective remain ordered.

Delivery slices:

1. **Conversation ledger and projection**
  - Checksummed append-only conversations, messages, immutable objective revisions, command proposals, admissions, and downstream correlations.
  - Stable IDs, content hashes, per-conversation sequence, client idempotency keys, recoverable replay, and typed APIs.
  - Objective states for candidate, awaiting admission, ready, active, paused, blocked, completed, cancelled, and superseded work.
2. **Interpretation and bounded context compiler**
  - A provider-neutral conversation profile with strict speech acts for discussion, inspection, objective formation, domain action, admission, control, status, and clarification.
  - Deterministic validation of every referenced conversation, message, objective, project, repository, proposal, and authority record.
  - Source-linked objective summaries, recent relevant dialogue, unresolved questions, current authority, and revision-pinned evidence compiled within the selected model budget.
  - Full model provenance, token usage, latency, prompt/output hashes, and provider-policy enforcement for every conversational inference.
3. **Typed domain-action adapters**
  - A closed capability registry declaring typed payload, read/mutation class, owning service, admission rule, allowed objective states, result type, and idempotency strategy.
  - Conductor adapters for genesis, work definition, staged planning, workflow start/cancel, repository analysis, coding progression, audit, promotion, standards, campaigns, and resolution.
  - Stable command IDs persisted as optional source references in downstream authority, or authority-specific deterministic adoption keys where extension is impossible.
  - Dispatch-intent records before service invocation and exact downstream ID/hash correlation afterward; recovery never matches by title, timestamp, or generated prose.
  - No direct store mutation and no duplicate conversational version of downstream authority.
  - Restart reconciliation when a domain mutation succeeds before conversational correlation is appended.
4. **Multi-objective scheduling and recovery**
  - Objective-scoped locks, acyclic dependencies, priority, resource admission, and independent-lane concurrency without a global chat mutex.
  - Run/case-scoped selection for analysis, coding, audit, and resolution workers where current service-wide mutexes prevent independent progress.
  - Destination-repository serialization and ancestry revalidation for promotion even when upstream worktrees execute concurrently.
  - Safe pause, resume, redirect, and cancellation semantics that preserve completed evidence and revalidate stale repository or policy authority.
  - Monotonic activity projection from asynchronous workers into the originating objective and conversation.
5. **Conversational desktop**
  - One restored chronological transcript with an objective rail, focus switching, state, dependencies, priority, pending admissions, diagnostics, and correlated evidence.
  - Inline review and admission of exact proposed actions plus links into existing detailed authority projections.
  - Cursor-based refresh is sufficient initially; transport streaming is not required for correctness.
6. **Repository and model onboarding gate**
  - Accept either an absolute local Git folder or a credential-free HTTP(S) Git URL through one admitted `ONBOARD_REPOSITORY` command.
  - Clone URLs only into deterministic Orchard-managed storage with prompts, submodules, and LFS smudging disabled; never execute repository code during onboarding.
  - Create or select the project, bind the canonical repository, preserve exact command identity, and recover retries without duplicate projects or clones.
  - Inspect installed model endpoints and bindings, register environment-referenced local or remote providers, and assign compatible bindings independently to conversation, definition, design synthesis, repository analysis, coding, and audit profiles.
  - Treat model installation or download as explicit machine setup; onboarding registers and verifies models but does not run package managers or `ollama pull`.
7. **Orchard-on-Orchard replacement proof**
  - Use one conversation to deliver three consecutive Orchard changes: a bounded defect, a cross-backend/frontend contract, and an authority change with persistence compatibility, tests, and an ADR.
  - Each change passes through planning, coding, verification, independent audit, acceptance, and local promotion.
  - Conversation, explicit admission, provider configuration, and inspection are allowed; repository source edits outside Orchard's admitted coding worktrees are not.
  - Report human interventions, failed attempts, elapsed time, model/provider usage, tokens, and final promoted evidence.

Exit evidence:

- Duplicate message delivery produces one durable message and at most one command or downstream action.
- Discussion cannot mutate authority, and model output cannot admit its own objective or action.
- Malformed, invented, ambiguous, stale, and cross-project references fail closed with a conversational clarification or diagnostic.
- Two independent objectives progress concurrently while one can be paused, redirected, or blocked without affecting the other.
- No hidden conversation-wide or worker-wide mutex serializes independent eligible objectives; same-destination promotion remains safely ordered.
- Objective dependencies remain acyclic and survive restart with exact state and priority.
- A crash between downstream mutation and correlation recovers without duplicate workspace entities, workflow runs, coding work, or promotion.
- Every mutating capability persists or deterministically resolves its source command identity; ambiguous adoption fails closed.
- A transcript larger than the selected model context remains operable through bounded, source-linked reconstruction.
- A compatible cloud-provider change preserves conversation, objective, and authority continuity.
- One conversation can plan one objective, execute another, observe an audit, and resolve a blocked objective without context leakage.
- Backend and desktop restart restore exact chronology, objective state, admissions, and correlated activity.
- Local-folder and URL onboarding produce one canonical bound project after retry or restart, reject credential-bearing URLs, and execute no repository-owned code.
- Workload-specific model assignments survive restart, reject incompatible context/capability budgets, and persist only credential references rather than secrets.
- The three defined Orchard-on-Orchard proof changes reach local promotion with no source edits outside Orchard worktrees and no bypass of the conductor or existing governance gates.
- Full backend/frontend build, clean diagnostics, compatibility tests, ADR acceptance, user/developer documentation, and committed milestone.

Non-goals:

- Treating the full transcript as one infinite model prompt.
- Letting chat records replace product, repository, evidence, audit, standards, or promotion authority.
- Unrestricted shell execution, silent mutation from ambiguous language, or model-authored admission.
- Remote multi-client control, cryptographic identity, quorum, signatures, or automatic Git push.
- Voice/mobile clients, distributed agent swarms, and automatic budget increases.
- Requiring the future symbol-aware evidence graph before the conductor can use existing revision-pinned repository analysis.

Implementation evidence recorded on 2026-07-18:

- Durable ledger, strict bounded interpretation, typed APIs, exact admission/correlation/reconciliation, source-linked summaries, and the persistent desktop conductor are implemented.
- Analysis, coding, audit, and campaign-resolution work use run/case-scoped execution; coding claims and generated IDs are allocated atomically in durable stores.
- Objective state and dependencies are revalidated at dispatch, and paused/dependent correlated runs are filtered and priority-ordered before production worker scheduling.
- Conversational model provenance is structured and checksum-covered; capability descriptors expose ownership, admission, projected result, and idempotency contracts.
- Conversation, coding-worker, company-circuit, desktop-client, compatibility, and full Gradle build validation pass with no editor diagnostics.
- Remaining completion gate: initialize admitted Orchard-on-Orchard authority and record the three required locally promoted changes, including intervention, provider/token, elapsed-time, failure, evidence, and bypass reports. ADR 044 remains `Proposed` until this proof succeeds.

Onboarding-gate evidence recorded on 2026-07-19:

- The conductor exposes admitted repository onboarding from absolute local folders or credential-free HTTP(S) Git URLs, with exact project command references and deterministic managed clone destinations.
- Managed cloning disables terminal prompts, submodule recursion, and LFS smudging and does not execute repository build or setup code.
- The conductor can inspect model configuration, register validated endpoint/binding pairs, and assign compatible bindings per execution profile without persisting credential values.
- Focused tests cover canonical local onboarding, restart idempotency, real HTTP Git cloning, embedded-credential rejection, model registration, compatible coding assignment, and incompatible-budget rejection.
- The remaining completion gate is unchanged: run the three required Orchard-on-Orchard changes through the newly onboarded authority and record the proof report.

### Milestone 10.3: Identity, Delegation, Quorum, and Signed Decisions

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

### Milestone 10.4: Verified Policy Sources and Deterministic Composition

- State: `PLANNED`
- Depends on: `10.1`, `10.3`

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

### Milestone 10.5: Resolution Action Executors and Policy Migration

- State: `PLANNED`
- Depends on: `10.1`, `10.4`

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
- Depends on: `10.5`

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
- Depends on: `12.1`, `10.4`

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
- Depends on: `10.2`, `10.3`, `13.0`

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
| 10.1 | Scoped Standards Overlays and Exception Authority | `COMPLETE` |

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
| 2026-07-18 | 3 | Prioritized a durable multi-objective conversational conductor as Milestone 10.2 and moved identity and later policy work behind the workflow-replacement proof. |
| 2026-07-18 | 2 | Completed scoped standards overlays and exception authority; selected identity, delegation, quorum, and signed decisions as Milestone 10.2. |
| 2026-07-18 | 1 | Established the canonical roadmap after Milestone 10.0; selected scoped standards overlays and exception authority as Milestone 10.1. |
