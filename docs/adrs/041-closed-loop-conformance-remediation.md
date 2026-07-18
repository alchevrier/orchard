# ADR 041: Closed-Loop Conformance Remediation

## Status

Accepted

## Context

Engineering standards and conformance scans can identify repository deficiencies and compile citation-backed candidate work. Completing a generated Task does not prove that its finding is resolved. The implementation may fail verification, violate architecture, remain nonconforming after promotion, or repair one practice while regressing another.

A closed loop must preserve Orchard's existing authority boundaries. Campaigns coordinate work but do not replace work definitions, design contracts, staged circuits, coding execution, verification evidence, independent audits, company acceptance, or local Git promotion.

## Decision

Explicit admission of a conformance backlog creates one durable remediation campaign. The campaign pins:

- the exact standard revision and hash;
- the seed scan, admission, and repository revision;
- every seed practice and disposition;
- each actionable practice's finding and candidate backlog nodes; and
- the exact workspace entity IDs created for those nodes.

Practice ID is the stable identity across scans. Model-generated finding IDs remain scan-local evidence and are never used as cross-revision closure authority.

A retry-safe campaign reconciler compiles admitted work through existing deterministic authorities. For each bounded Task, Bug, or Investigation leaf it:

1. activates design governance when needed;
2. admits correlated Epic, Story, and leaf designs with inherited requirement tracing;
3. creates a READY work definition from admitted findings, acceptance criteria, affected paths, and verification commands;
4. admits sequential Story and Epic staged plans covering the complete generated hierarchy; and
5. lets existing dispatch, broad analysis, bounded coding, verification, independent audit, acceptance, and promotion services execute the work.

Only one leaf is compiled at a time. Another leaf cannot be compiled until every prior linked workflow run has an accepted local promotion. This prevents multiple isolated candidates from sharing and later conflicting against one destination base revision.

After a campaign candidate is accepted, the reconciler performs local promotion through `CompanyControlService`. The promoted clean repository revision is then scanned against the campaign's pinned standard revision before any next leaf is compiled.

Campaign evaluations are append-only and idempotent by campaign ID plus repository revision. A campaign becomes:

- `CLOSED` only when every linked remediation practice is conforming, not applicable, or covered by an admitted active exception, and no seeded practice regressed;
- `IN_PROGRESS` when the promoted revision still has unresolved linked practices and an uncompiled admitted leaf remains;
- `BLOCKED` when the promoted revision remains nonconforming after all admitted leaves are exhausted; or
- `ESCALATED` when evidence is unknown or conflicting, or a previously resolved seeded practice regresses.

Campaign creation is reconciled both immediately after explicit backlog admission and by a periodic scheduler. Periodic reconciliation also recovers missed triggers after process failure. Campaign state, promoted revisions, practice transitions, and regression evidence are projected in the existing engineering-standards cockpit.

## Consequences

- Work completion and finding closure are separate, inspectable facts.
- Closure is proven only by a new content-hashed scan of an accepted promoted revision.
- Broad analysis is reused for repository-wide correlation after each promotion.
- Bounded coding remains limited to admitted execution plans and one remediation slice.
- Campaign progress survives restart in checksummed append-only authority.
- Duplicate scheduler and operator ticks cannot duplicate campaign evaluations.
- Orchard can run the same improvement loop against its own repository after binding a clean checkout and admitting product genesis.
- Regressions outside the repaired practice remain visible and prevent silent closure.

## Boundaries

- Campaign admission remains explicit. Model output cannot create a campaign or mutate workspace authority directly.
- Product genesis and all existing workflow/design gates remain mandatory; a campaign cannot bypass project readiness.
- The current 32-entity workspace capacity still limits candidate backlog admission.
- `BLOCKED` and `ESCALATED` campaigns require a later human or Architect decision; automatic successor backlog generation is deferred.
- Organization, module, and scoped-exception standard overlays remain future authority layers.
- Campaign cost and latency accounting are not yet persisted as first-class authority.
- The reconciler evaluates only clean local promoted revisions and never pushes a remote.

## Alternatives Considered

### Close findings when generated Tasks are done

Rejected because workflow completion proves accepted implementation evidence for a bounded task, not repository-wide conformance to the standard.

### Run every remediation leaf in parallel

Rejected because independent candidates would share the same base revision and compete for one local destination branch. Sequential promoted slices preserve exact revision authority.

### Let the campaign service execute coding directly

Rejected because it would duplicate and weaken existing repository analysis, coding, verification, audit, and promotion controls.

### Re-scan against the latest standard revision

Rejected because policy changes would make campaign closure non-reproducible. A campaign evaluates the immutable standard revision that created it; a newer standard requires its own scan and campaign.
