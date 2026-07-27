# Architecture

## Runtime Shape

Orchard is a local desktop application with one backend process and one Compose Desktop process.

```mermaid
flowchart LR
    UI[Compose Desktop] -->|typed HTTP| W[Workspace API :8085]
    W --> S[Governed services]
    S --> P[File authorities under ~/.orchard]
    S --> G[Bound Git repositories and worktrees]
    S --> M[Ollama or OpenAI-compatible providers]
```

`OrchardApplication.kt` is the backend composition root. It initializes paths, constructs file-backed stores, composes services, launches periodic workers, and starts one loopback-only Ktor Netty server.

## Module Boundaries

### Backend

The backend owns authority and mutation.

- `workspace`: entity hierarchy, workflow memory, definitions, plans, dispatch, design, genesis, and repository bindings.
- `company`: company state, staffing, orchestration, independent audits, and local promotion.
- `analysis`: broad repository analysis and revision-pinned execution plans.
- `agent`: typed coding operations, worktree application, verification, and repository context collection.
- `standards`: base standards, scoped policy composition, exception authority, conformance scans, remediation campaigns, and resolution cases.
- `vector`: provider catalog, model profiles, provider clients, and routing.
- `resource`: machine telemetry, policy, and execution leases.
- `config`: local storage roots.

The `api`, `domain`, `compilation`, `storage`, `service`, and `ipc` packages contain supporting boundaries and earlier platform capabilities. New behavior should attach to the authority that owns its invariant, not merely the nearest route.

### Frontend

The frontend is a projection and command surface. `DesktopNetworkClient` owns typed HTTP integration. `OrchardCircuitBinder` loads state and issues commands. Compose views render backend truth and local edit buffers.

Do not put authoritative transition logic in Compose. A disabled button can improve usability, but the backend must independently validate every request.

## Authority Flow

The principal delivery flow is:

```mermaid
flowchart TD
    Intent[Human intent] --> Candidate[Model or human candidate]
    Candidate --> Validation[Deterministic validation]
    Validation --> Admission[Explicit admission]
    Admission --> Analysis[Revision-pinned repository analysis]
    Analysis --> Coding[Typed coding operations in worktree]
    Coding --> Verify[Admitted verification commands]
    Verify --> Audit[Independent architecture and quality audit]
    Audit --> Accept[Company acceptance]
    Accept --> Promote[Local fast-forward promotion]
    Promote --> Scan[Follow-up repository evidence]
```

Each arrow is a boundary with its own record and failure state. Avoid helper methods that collapse proposal, admission, execution, and acceptance into one mutation.

Candidate PRs pin the actual candidate revision, compiled work package, changed paths, claims, checks, and evidence. Corrective candidates link to their immutable parent PR. Typed candidate reviews are separate append-only records for `CODE`, `INTENT`, `DESIGN`, and `INTEGRATION` authority; each finding identifies evidence and a correction target. Bounded automated reviewer actors receive distinct role envelopes and resource leases, then can only submit those typed reviews. Orchard compiles findings into immutable target-specific correction authority: repair, work-package recompilation, design revision, clarification, and escalation each dispatch independently; no target silently becomes a coding retry.

Candidate lifecycle is also append-only. A candidate begins `REVIEW_REQUIRED`; four conforming independent reviews record `ACCEPTED`; a nonconforming review records `REPAIR_REQUIRED`; an independent audit violation can record `BLOCKED`; and a corrective child marks its immutable parent `SUPERSEDED`. Derived candidate-learning episodes retain terminal outcomes and pinned authority hashes for retrieval, but cannot alter any operational authority.

Standards have a second deterministic flow:

```mermaid
flowchart TD
    Base[Immutable base standard] --> Compose[Scope overlay composition]
    Overlay[Organization to work-item overlays] --> Compose
    Compose --> Effective[Hash-pinned effective standard]
    Request[Exception request] --> Proposal[Evidence-bound proposal]
    Proposal --> Admission[Explicit exception admission]
    Admission --> Active[Scope, policy, Git, time, evidence, revocation checks]
    Effective --> Scan[Repository conformance scan]
    Active --> Scan
    Scan --> Campaign[Policy-aware campaign evaluation]
```

`StandardsPolicyAuthority` owns immutable records and pure composition. `StandardsPolicyService` owns repository-bound lifecycle validation. `EngineeringStandardsService` supplies only deterministic active admissions to the model and rejects unsupported `EXCEPTION_ACTIVE` output. Campaign identity includes policy authority so unchanged Git revisions can be reevaluated after authority changes.

## Background Workers

The backend starts supervised coroutine loops at one-second intervals for:

- eligible circuit dispatch;
- repository analysis;
- governed coding;
- independent audit; and
- remediation campaign, resolution, and exception-request reconciliation.

Manual tick endpoints exist for selected workers and tests. Production correctness must be idempotent because a process can stop after an external mutation but before its corresponding ledger append. Resolution admission, promotion, and campaign reconciliation therefore match durable evidence before creating successors or recording completion.

## Repository Boundary

Orchard binds canonical local Git roots. Dispatched work uses isolated managed worktrees. Repository context collection reads tracked text, ranks evidence by query relevance and foundation weight, and applies bounded byte/file budgets.

Foundation context includes root governance files such as `README.md`, `ROADMAP.md`, and tracked documentation under `docs/`. Foundation weight improves selection but does not waive context limits or content-hash pinning.

## Model Boundary

Model calls are routed through named execution profiles and provider bindings. Services provide bounded prompts and expect structured JSON. Outputs are decoded into candidate envelopes, checked for shape, IDs, hashes, coverage, and policy, then either returned for explicit admission or used within an already admitted bounded operation.

Never treat successful JSON decoding as sufficient validation.

## Failure Model

Orchard generally fails closed:

- malformed requests return 4xx responses;
- stale revisions and dirty repositories block mutation;
- model/resource/storage failures do not invent success records;
- committed-prefix ledger corruption stops replay;
- a malformed final JSONL append may be quarantined where the store uses recoverable replay; and
- interrupted operations reconcile from durable repository/workspace evidence.

See [Persistence and Recovery](persistence.md) for store-level rules and [ADRs](../adrs/) for the decisions behind these boundaries.
