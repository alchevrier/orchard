# ADR 042: Campaign Resolution and Successor Governance

## Status

Accepted

## Context

Closed-loop conformance remediation can prove that a promoted revision remains nonconforming, contains conflicting or unknown evidence, or regresses a previously resolved practice. Milestone 9.9 correctly stops such campaigns as `BLOCKED` or `ESCALATED`, but a terminal state alone does not provide governed recovery.

Automatically generating and executing more work would repeat the same failed assumptions and let model output reopen delivery authority. Silently changing the engineering standard or granting an exception would be a stronger authority violation. Orchard needs an immutable decision layer between terminal evidence and any successor action.

## Decision

Every terminal `BLOCKED` or `ESCALATED` campaign evaluation opens exactly one append-only resolution case. The case pins:

- the predecessor campaign and project;
- the exact terminal evaluation ID and hash;
- the exact promoted repository revision;
- the affected stable practice IDs; and
- a deterministic cause: remediation exhausted, evidence conflict, evidence unknown, or regression.

The Architect may generate one or more candidate resolution proposals against that case. Generation uses the broad repository-analysis execution profile, provider-neutral model routing, machine resource admission, strict JSON decoding, and content-hashed model provenance. The supplied terminal evaluation, pinned standard, follow-up scan, allowed verification commands, and action vocabulary are the complete model authority.

A proposal selects exactly one action:

- `ADDITIONAL_REMEDIATION` for bounded implementation work supported by current evidence;
- `INVESTIGATION` for bounded evidence-gathering work;
- `RESCAN` to request fresh evidence;
- `EXCEPTION_REQUEST` to request separate human policy authority;
- `STANDARD_CLARIFICATION` to request a prospective standard decision; or
- `ABANDON` to end the lineage with an explicit rationale.

Delivery proposals contain one structurally valid Epic, Story, and leaf hierarchy. Every node references only practices in the terminal case, every selected practice is covered, and verification commands must come from the terminal scan. Non-delivery proposals cannot contain backlog nodes.

Model proposals remain non-authoritative. Explicit admission is required and succeeds only while the proposal still pins the latest terminal evaluation and the clean repository remains at that evaluation's exact revision.

For admitted additional remediation or investigation, Orchard:

1. creates the exact proposed hierarchy through the existing atomic workspace batch boundary;
2. persists exact proposal-node to workspace-entity mappings;
3. appends the resolution admission; and
4. reconciles one new remediation campaign linked to the predecessor, resolution case, proposal, admission, terminal scan, and created entities.

The successor campaign reuses the existing definition, design, staged-plan, repository-analysis, bounded-coding, verification, independent-audit, promotion, and follow-up-scan authorities. Historical campaign and evaluation records are never mutated.

Admission recovery is idempotent. If the workspace batch commits but resolution persistence fails, the retry finds the complete entity batch by proposal hash and adopts those exact entities before appending one admission and one successor campaign.

Admitted rescan, exception request, standard clarification, and abandonment proposals create no repository entities and no successor campaign. Their admission proves only that the decision or request was accepted; it does not claim that a scan ran, an exception exists, or a standard changed.

Persistent case reconciliation runs after campaign evaluation and at the periodic campaign tick. Successor reconciliation materializes only already admitted delivery decisions and recovers missed post-admission triggers after restart.

## Consequences

- Terminal campaigns become governed decision points instead of dead ends.
- Orchard can reason autonomously about recovery without autonomously granting itself authority.
- Predecessor and successor campaigns form an inspectable lineage.
- Repeated remediation cannot erase the evidence that prior remediation failed.
- Repository drift blocks stale admission before workspace or campaign mutation.
- Crash recovery cannot duplicate successor work or campaign authority.
- Conflict, uncertainty, regression, and simple exhaustion remain distinguishable in durable state.
- The cockpit projects terminal cause, affected practices, Architect rationale, explicit admission, and successor lineage.
- Existing Milestone 9.9 campaign JSONL remains hash-compatible because absent successor source data is not encoded.

## Boundaries

- Resolution proposal generation is explicit rather than automatic to avoid consuming broad-analysis resources for every terminal case without operator intent.
- `RESCAN` admission does not yet schedule a new scan automatically.
- `EXCEPTION_REQUEST` does not grant an exception; scoped exception authority is deferred to standards overlays.
- `STANDARD_CLARIFICATION` does not mutate the pinned standard; a new standard revision remains a separate explicit update.
- `ABANDON` records a governed decision but does not delete findings, campaigns, work, or repository evidence.
- Resolution cases currently accept one admitted decision. Superseding an admitted decision requires a future revisioned decision authority.
- The existing 32-entity workspace capacity still bounds successor delivery admission.
- Authentication, named organizational roles, quorum, and signatures remain future authority layers.

## Alternatives Considered

### Automatically retry blocked campaigns

Rejected because remediation exhaustion is evidence that the current plan was insufficient. Repeating it without a new admitted decision is neither autonomous reasoning nor governed recovery.

### Let the Architect directly create successor work

Rejected because model output is candidate data. Deterministic validation and explicit admission must remain the mutation boundary.

### Mutate the original campaign with new work

Rejected because that would rewrite the meaning of a campaign after its terminal evaluation and obscure why the first remediation failed.

### Encode successor work as a synthetic conformance scan

Rejected because a proposal is not repository evidence. Successor source authority is modeled explicitly and pins the real terminal follow-up scan.

### Grant exceptions or modify standards during resolution admission

Rejected because policy authority is distinct from delivery recovery. Resolution may request those decisions but cannot impersonate them.
