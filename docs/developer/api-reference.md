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
| `POST` | `/api/workflow-runs/{runId}/coordinate-successor` | Deterministically prepare one coordinate-pinned successor task and unaccepted proposal for a legacy run |
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

Wildcard selectors are discovery input, not admissible delivery authority. Proposal recording automatically attempts deterministic repair against the bound repository's committed intelligence manifest: selectors become exact paths and linked typed coordinates, with `coordinateRepairEvidence` recording revision, manifest versions, node IDs, and source hashes. Repair is atomic and has no fixed path-count cap; empty, incomplete, or unsupported discovery retains the draft with `coordinateRepairDiagnostic` instead of truncating scope. Model context budgets are enforced separately and do not authorize dropping required coordinates. Unrepaired wildcard definitions assess `NEEDS_INVESTIGATION`, never `READY`, and workflow start reassesses historical definitions under the current policy. Repair does not grant acceptance.

For a legacy run lacking coordinate admission, the pilot advertises `prepare-coordinate-successor`. Recovery uses the current committed manifest, uniquely resolved file owners named in scope, and proven import/test/dependency neighbors within selector boundaries to generate exact coordinates. Explicit paths are never discarded; ambiguous owners require clarification. The endpoint creates a separate successor work item and unaccepted proposal carrying `successorOfRunId`, `coordinateRepairRevision`, and `coordinateRepairVersion`. Unresolved repairs are also persisted with diagnostics and questions. An unchanged basis returns the same proposal across restart, and the pilot requests clarification instead of repeating failed repair. A changed repository revision or repair capability version permits a new linked proposal revision on the same successor work item; accepted or started successors are not silently replaced. Historical runs and definitions remain unchanged. Successful repair still requires human acceptance and separate delivery admission. No model call or retry authority is consumed by repair.

Model-backed repository analysis may also require `repositoryCoordinates`. Each coordinate has a stable `coordinateId`, one exact repository-relative `path`, and linked `scopeIndexes`. Coordinates are graph-query roots; they must not contain wildcard syntax. Selectors remain complete-scope validation inventory and do not automatically authorize model context. At workflow start, Orchard ensures a compatible manifest for the pinned revision and persists the admitted coordinate node IDs, source hashes, extractor version, and policy version in the immutable workflow context. A missing, unresolved, ambiguous, unsupported, or stale coordinate returns `422 Unprocessable Entity` and creates no run. Repository analysis and pilot authorization both verify that persisted evidence still matches the compatible manifest. The resulting execution-plan provenance records each resolved coordinate alongside the manifest and graph-context trace.

Unresolved imports marked `REQUIRE_EXPLICIT_CONTEXT` do not make a content-addressed source coordinate unsupported. Their boundary IDs remain visible in graph-context traces; admission does not invent dependency edges or certify complete dependency resolution. Other unsupported boundaries, including reflection and configuration-selected implementations, still block coordinate admission and identify the affected paths.

Ticket-scoped project report revisions include typed evidence for candidate PRs, independent audits, company acceptance, and local promotion. Each new governed-delivery record changes the report source hash, producing an immutable inbox revision that remains correlated with the ticket's canonical conversation thread.

When the source task belongs to a started staged plan, coordinate recovery creates a corrective bug under the same story rather than adding an ordinary task to the frozen plan. The bug records the blocked-run reproduction, original regression outcome, and successor lineage. The admitted plan and source run remain unchanged; the correction still requires definition acceptance and its own delivery admission. An accepted source-pinned correction bug with ready coordinate evidence may start outside the frozen circuit in an isolated non-integration-owner workspace. Ordinary tasks and bugs remain circuit-gated; project genesis, design, current repository, and coordinate admission checks still apply to corrections.

## AI Operator Pilot

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/api/pilot/status` | Compact deterministic projection of the active objective, run, operation, model phase, evidence gaps, resources, and admitted next actions |
| `GET` | `/api/pilot/state-atlas` | Machine-readable map of Orchard states, legal transitions, authorities, evidence gates, resource predicates, and operator meanings |

The pilot status does not invoke a model and does not create authority. It composes existing Orchard projections so a human or AI operator can determine what is happening and what may happen next without reading implementation code. Provider activity is correlated to repository analysis by prompt hash and excludes prompt and response content.

## Repository Analysis and Coding

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/api/repository-analysis/plans` | List repository execution plans |
| `GET` | `/api/repository-analysis/attempts` | List durable running ownership, terminal analysis blocks, and retry authorizations |
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

Repository-analysis requests compile an analysis-stage Attention projection from the admitted definition before graph selection. Scope coordinates, selector relationships, and related graph paths govern source selection. The final model envelope includes candidate/evidence-only correlations and explicit deferred or unresolved evidence after compaction. Source-change nominations must trace to supplied candidate evidence, and citations cannot claim evidence absent from the final projection. Analysis grants reasoning permissions, not coding mutation authority; deterministic plan admission remains authoritative.

Coding source collection follows active Attention correlations, read-only citations, and accepted prerequisite owners. Its retrieval query uses active frame behavior and evidence rather than deferred-plan instructions. Final context path binding is checked before inference, while work-package ownership and the existing frame path/action guard continue to bound mutations.

Analysis uses `repositoryContext.files` as the canonical supplied-source path/hash/excerpt table, with scope relationships in `attention.correlations`; redundant required-evidence and scope inventories are not serialized into the model envelope. Analysis and coding recheck source adequacy after compaction. Abbreviated Kotlin must retain complete parsed declarations or script statements, not headers or partial bodies. Complete files, including empty scaffolds, remain admissible; abbreviated unsupported code languages are rejected. These checks do not establish universal semantic sufficiency.

Provider output schemas are selected by explicit `ModelOutputContract` values, not markers embedded in source or prompt text. Analysis uses the candidate contract; coding explicitly chooses its bounded batch, literal-replacement, or generic proposal mode. Service-side strict decoding and mutation authority checks remain mandatory after provider formatting.

Context quality and JSON schema validity are independent. A schema-valid coding result denied by the Attention action guard records failed downstream quality, not success. Later execution outcomes not yet established when the model observation is appended remain `UNKNOWN`; final coding results are recorded separately in the coding execution ledger. Failed source compilation persists a blocked analysis attempt and quality report before inference, and restart must preserve that report without rewriting the ledger or granting retry authority.

Analysis attempt `contextSelection` may include `tokenAccounting`, `componentAccounting`, `budgetPolicy`, `minimumEnvelopeAccounting`, `qualityPolicy`, and `evidenceDiagnostics`; model observations may include `inputAccounting`. Accounting reports `contentBytes`, `contentTokens`, `providerOverheadTokens`, `totalTokens`, `method`, and optional `tokenizerId`. Supported binding encodings use ordinary BPE content counts plus a 512-token input reserve, not exact provider-template measurement. Unsupported encodings use an explicitly labeled `UTF8_BYTE_UPPER_BOUND` fallback. Component counts are attribution estimates and need not sum to the full prompt count. Ordinary analysis retains its named 70% allocation; source-byte guards and user aperture overrides remain separate.

Required-path absence and inadequate source are reported as `CONTEXT_UNAVAILABLE`, with source diagnostics such as `SOURCE_EVIDENCE_EMPTY`, `SOURCE_EVIDENCE_INADEQUATE`, and `SOURCE_EVIDENCE_UNSUPPORTED`. If analysis compaction cannot fit complete required evidence while metadata fits, it reports inadequacy rather than generic overflow. A tokenizer-counted metadata baseline exceeding the allowance reports `CONTEXT_BUDGET_EXCEEDED`; byte-fallback uncertainty instead reports that capacity could not be established. Historical records omit absent new accounting fields and are not rewritten. See [ADR 056](../adrs/056-quality-governed-model-context-compilation.md) for policy limits and parser compatibility warnings.

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
