# API Reference

The backend exposes JSON over one loopback-only Ktor server. The Compose desktop is the primary client; the HTTP surface is not currently versioned as a public remote API.

## Servers

| Server | Base URL | Routes |
| --- | --- | --- |
| Workspace API | `http://127.0.0.1:8085` | `/api/*` |

The server installs `ContentNegotiation` with `kotlinx.serialization`. Decoding ignores unknown JSON keys. Request and response DTOs live beside backend routes and in the frontend typed client; inspect both before changing a contract.

## Workspace and Company

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/api/workspace` | Complete workspace projection |
| `GET` | `/api/company/state` | Durable company control state |
| `GET` | `/api/company` | Company workspace projection |
| `POST` | `/api/projects/{projectId}/company/start` | Start governed company execution |
| `POST` | `/api/company/runs/{runId}/promotion` | Promote an accepted candidate locally |

## Genesis, Design, and Repository

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/api/projects/{projectId}/genesis` | Record a genesis revision |
| `POST` | `/api/projects/{projectId}/genesis/admission` | Admit an exact genesis revision |
| `POST` | `/api/projects/{projectId}/genesis/proposal` | Generate a candidate genesis phase proposal |
| `POST` | `/api/projects/{projectId}/design-governance` | Activate project design governance |
| `POST` | `/api/designs` | Record a design requirement revision |
| `POST` | `/api/designs/{designId}/admission` | Admit or reject a design |
| `PUT` | `/api/projects/{projectId}/repository` | Bind a canonical local repository |

## Definition, Planning, and Workflow

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/api/work-items/{workItemId}/definitions` | Record a work definition |
| `POST` | `/api/work-items/{workItemId}/definition-proposals` | Generate a definition proposal |
| `POST` | `/api/definition-proposals/{proposalId}/feedback` | Add proposal feedback |
| `POST` | `/api/definition-proposals/{proposalId}/accept` | Accept a proposal, optionally with edited definition |
| `POST` | `/api/staged-plans` | Record a staged delivery plan |
| `POST` | `/api/staged-plan-proposals/{scopeId}/generate` | Generate a staged plan proposal |
| `POST` | `/api/staged-plan-proposals/{proposalId}/accept` | Accept a staged plan proposal |
| `POST` | `/api/work-items/{workItemId}/runs` | Start a workflow run |
| `POST` | `/api/workflow-runs/{runId}/evidence` | Submit evidence |
| `POST` | `/api/workflow-runs/{runId}/attempts` | Record an attempt |
| `POST` | `/api/workflow-runs/{runId}/criterion-judgments` | Submit a human criterion judgment |
| `POST` | `/api/workflow-runs/{runId}/cancel` | Cancel a run |

Work definitions may include `repositoryEvidenceSelectors`. Each selector binds stable `scopeIndexes` to repository-relative `pathGlobs` and optional exact `contentLiterals`. `ALL_MATCHES` requires every matching file; `AFFINE_TEST` selects the matching test path with the strongest common path prefix to its `affinitySelectorId`. Empty selector lists are omitted so historical definition hashes remain unchanged.

Ticket-scoped project report revisions include typed evidence for candidate PRs, independent audits, company acceptance, and local promotion. Each new governed-delivery record changes the report source hash, producing an immutable inbox revision that remains correlated with the ticket's canonical conversation thread.

## Repository Analysis and Coding

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/api/repository-analysis/plans` | List repository execution plans |
| `GET` | `/api/repository-analysis/attempts` | List durable terminal analysis blocks and retry authorizations |
| `POST` | `/api/repository-analysis/tick` | Run one analysis reconciliation tick |
| `POST` | `/api/repository-analysis/runs/{runId}/retry` | Authorize one successor attempt for a blocked analysis run |
| `GET` | `/api/coding-worker/executions` | List coding execution records |
| `GET` | `/api/coding-worker/pull-requests` | List immutable candidate PR review artifacts |
| `GET` | `/api/coding-worker/pull-request-reviews` | List immutable candidate PR reviews; optionally filter with `pullRequestId` |
| `POST` | `/api/coding-worker/pull-request-reviews` | Record one typed code, intent, design, or integration review for an existing candidate PR |
| `GET` | `/api/coding-worker/pull-request-corrections` | List deterministic correction authority compiled from review findings; optionally filter with `pullRequestId` |
| `GET` | `/api/coding-worker/pull-request-dispositions` | List immutable candidate PR lifecycle dispositions; optionally filter with `pullRequestId` |
| `GET` | `/api/coding-worker/pull-request-learning` | List derived terminal candidate outcome episodes; optionally retrieve matching episodes with `query` |
| `POST` | `/api/coding-worker/pull-request-learning/tick` | Derive any unrecorded terminal candidate learning episodes |
| `GET` | `/api/coding-worker/pull-request-correction-dispatches` | List terminal candidate-repair dispatch records |
| `POST` | `/api/coding-worker/pull-request-corrections/tick` | Reconcile one eligible candidate-repair correction into the existing repair workflow |
| `POST` | `/api/coding-worker/pull-request-work-package-recompilations/tick` | Recompile one pinned package correction successor |
| `POST` | `/api/coding-worker/pull-request-design-revisions/tick` | Create one pinned design-revision request |
| `POST` | `/api/coding-worker/pull-request-clarifications/tick` | Dispatch one clarification correction and block its candidate pending response |
| `POST` | `/api/coding-worker/pull-request-escalations/tick` | Dispatch one escalation correction and block its candidate pending governance response |
| `POST` | `/api/coding-worker/pull-request-reviews/automated/tick` | Run one bounded independent automated review actor |
| `POST` | `/api/coding-worker/tick` | Run one coding reconciliation tick |

Deterministic analysis rejection and model failure append a blocked attempt tied to the run and pinned repository revision. Blocked runs are excluded from automatic reconciliation until the retry endpoint appends explicit successor authority; each unsuccessful successor blocks again without deleting prior attempts.

A terminal coding block tied to the exact current plan ID and hash makes repository analysis eligible to produce a successor plan on the same pinned repository revision. The coding rejection diagnostic is included in the authoritative analysis envelope. Retry authorization on the blocked plan suppresses reanalysis until that authorized coding attempt reaches a terminal outcome.

Candidate PR successors carry an optional immutable `parentPullRequestId`, creating a repair lineage without changing prior candidate records. A review submission pins the exact candidate PR hash and has one of `CODE`, `INTENT`, `DESIGN`, or `INTEGRATION` kinds. Findings identify their criterion, observation, severity, evidence hashes, and correction target.

Orchard deterministically compiles each review into one immutable correction artifact per target and reconciles interrupted compilation on restart. Candidate repair, work-package recompilation, design revision, clarification, and escalation each have target-specific dispatchers. Clarification and escalation append a terminal dispatch while blocking the candidate pending an explicit response; neither becomes a coding retry. Automated reviewers use distinct bounded envelopes for code, intent, design, and integration review, submit only through the typed review service, and cannot admit, accept, or promote.

Candidate lifecycle disposition is separately append-only: a frozen candidate begins `REVIEW_REQUIRED`; a nonconforming review records `REPAIR_REQUIRED`; all four conforming independent reviews record `ACCEPTED`; and freezing a parent-linked successor records the parent as `SUPERSEDED`. An independent audit violation may subsequently record `BLOCKED`, preventing stale review acceptance from reaching promotion. Derived outcome-learning episodes pin the candidate, review, correction, and disposition hashes and remain retrieval-only.

## Standards, Campaigns, and Resolution

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/api/projects/{projectId}/engineering-standards` | Get standards and scan projection |
| `PUT` | `/api/projects/{projectId}/engineering-standards` | Save a new standards revision |
| `GET` | `/api/projects/{projectId}/standards-policy` | Get effective policy, overlays, and exception lifecycle |
| `POST` | `/api/projects/{projectId}/standards-overlays` | Append an immutable scoped overlay revision |
| `POST` | `/api/projects/{projectId}/standards-exception-proposals` | Propose an evidence-bound scoped exception |
| `POST` | `/api/standards-exception-proposals/{proposalId}/admission` | Admit an exact exception proposal and time window |
| `POST` | `/api/standards-exception-admissions/{admissionId}/revocation` | Revoke admitted exception authority |
| `POST` | `/api/projects/{projectId}/conformance-scans` | Scan a clean repository revision under effective policy |
| `POST` | `/api/conformance-scans/{scanId}/admission` | Admit candidate remediation backlog |
| `GET` | `/api/projects/{projectId}/remediation-campaigns` | List campaign projections |
| `POST` | `/api/remediation-campaigns/tick` | Run campaign/resolution reconciliation |
| `GET` | `/api/projects/{projectId}/campaign-resolutions` | List resolution cases and decisions |
| `POST` | `/api/campaign-resolution-cases/{caseId}/proposals` | Generate a resolution proposal |
| `POST` | `/api/campaign-resolution-proposals/{proposalId}/admission` | Admit a resolution decision |

Policy projection and scan routes default to project scope. Supply `modulePath` for a module target, `workItemId` for a work-item target, or both to compose a work item through its module ancestry. Overlay and exception request bodies carry an explicit `StandardPolicyScope`; the backend rejects project leakage and invalid combinations. An organization overlay uses an organization scope with no project ID and becomes applicable across local projects.

Exception admission never changes the proposal's requested scope, practices, policy hash, repository revision, controls, or evidence. Revocation appends a separate record. Read the projected state rather than inferring current effect from the presence of an admission record.

## Models and Resources

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/api/model-profiles` | List execution profiles and settings |
| `PUT` | `/api/model-profiles/{profileId}` | Update profile settings |
| `GET` | `/api/model-providers` | Get provider catalog and policy |
| `PUT` | `/api/model-providers` | Replace validated provider catalog |
| `GET` | `/api/model-providers/inspection` | Inspect configured endpoints/models |
| `GET` | `/api/machine-resources` | Get capacity, usage, and policy projection |
| `PUT` | `/api/machine-resources/policy` | Update resource admission policy |

## Status Semantics

Routes map domain outcomes instead of returning `200` for every result. Common meanings are:

| Status | Meaning |
| --- | --- |
| `200 OK` | Projection, accepted update, or idempotent successful result |
| `201 Created` | New durable record or completed candidate creation |
| `400 Bad Request` | Request body cannot be decoded |
| `404 Not Found` | Referenced project, work item, run, proposal, or case is absent |
| `409 Conflict` | Stale revision, busy worker, already-decided state, drift, or illegal current-state conflict |
| `422 Unprocessable Entity` | Decoded request violates a domain invariant |
| `429 Too Many Requests` | Resource or concurrency admission is blocked |
| `503 Service Unavailable` | Storage, model, telemetry, application, or verification dependency failed |

Read the structured response body for the domain status and diagnostic. Clients must not infer admission or completion from the HTTP class alone.

## Contract Change Checklist

1. Change the owning service result and backend DTO.
2. Update route decoding and status mapping.
3. Update `DesktopNetworkClient` DTOs and method.
4. Update the binder and projection where user-visible.
5. Add backend and frontend client tests.
6. Update this reference and the relevant user workflow.
