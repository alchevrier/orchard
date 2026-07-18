# Governance

Orchard separates proposals, admissions, execution, evidence, acceptance, and promotion so no model can turn its own assertion directly into product authority.

## Authority Layers

The practical precedence is:

1. Admitted runtime records and revision-pinned evidence.
2. Accepted architecture decisions and hard integrity invariants.
3. Admitted product, design, work, plan, policy, and standards revisions.
4. Model proposals awaiting deterministic validation or explicit admission.
5. Roadmap and guide text as planning or explanatory context.

A guide can explain a command but cannot authorize it. A roadmap can name the next milestone but cannot create its work.

## Proposal Versus Admission

A proposal is inspectable candidate data. Examples include:

- product genesis proposals;
- work-definition proposals;
- staged circuit proposals;
- conformance backlog proposals; and
- campaign resolution proposals.

Admission pins the accepted candidate to its source hashes, revision, and actor. Orchard revalidates stale or drift-sensitive inputs before mutation.

## Design and Delivery Gates

Production work requires admitted product genesis. Governed work can additionally require:

- admitted design requirements;
- a Ready Work Definition;
- a staged plan;
- revision-pinned repository analysis;
- typed coding operations;
- required automated and human evidence;
- independent architecture and quality audits;
- company acceptance; and
- local promotion.

Coding cannot submit its own human judgment, mark a run complete directly, approve an audit, or promote itself.

## Standards and Conformance

Engineering standards begin as immutable project revisions with stable practice IDs. Scoped overlays then compose an effective standard in this precedence order:

1. organization;
2. project;
3. module; and
4. work item.

An overlay can add, tighten, or disable a practice. A narrower scope may strengthen inherited policy. It cannot disable a mandatory floor; Orchard reports a composition conflict and blocks scanning instead of guessing which policy should win.

A scan judges every enabled effective practice against a clean repository revision and records exact citations. It also pins the effective-standard hash and every applied exception admission, so later policy changes do not rewrite what governed historical evidence.

`UNKNOWN` means evidence is insufficient. `CONFLICTING` means supplied evidence supports incompatible conclusions. Neither should be converted into confident implementation work without investigation or resolution.

## Exception Authority

An exception proposal identifies exact practices, scope, effective policy, repository revision, rationale, compensating controls, content-hashed evidence, and requested time bounds. It has no effect until explicitly admitted.

Admission adds the grantor and exact activation/expiry window. Orchard then evaluates the authority continuously:

| State | Meaning |
| --- | --- |
| `PENDING` | The proposal is not admitted or its activation time has not arrived. |
| `ACTIVE` | Scope, policy, time, Git ancestry, evidence, and revocation checks all pass. |
| `EXPIRED` | The admitted expiry time passed. |
| `REVOKED` | A revocation became effective. |
| `SUPERSEDED` | The effective policy hash changed. |
| `INVALIDATED` | Repository ancestry or compensating-control evidence no longer validates. |

`EXCEPTION_ACTIVE` is truthful only when deterministic active authority covers that practice and scan scope. An active exception permits that disposition when the deviation remains; it does not prevent a scan from reporting `CONFORMING` when repository evidence independently satisfies the practice.

An admitted campaign `EXCEPTION_REQUEST` creates one candidate proposal linked to its resolution evidence. It never grants itself. Review the generated scope, practices, controls, evidence, and expiry before admission.

## Campaign Closure

A remediation campaign closes only when a promoted follow-up scan proves every linked practice is resolved and no seeded practice regressed.

Closure remains policy-sensitive. Admission, expiry, revocation, supersession, or invalidation can trigger another scan and evaluation at the same promoted Git revision because the governing authority changed even though repository bytes did not.

The following do not prove closure:

- a Task reaching Done;
- a coding model claiming success;
- a passing command without the required complete evidence set;
- an accepted candidate that was not promoted; or
- a scan of a different repository or standard revision.

## Terminal Resolution Decisions

A terminal campaign opens one case pinned to its latest evaluation. The Architect may propose one bounded action, but admission is explicit.

Delivery actions create successor work and a linked campaign. Non-delivery actions have narrower meaning:

| Action | What admission means | What it does not mean |
| --- | --- | --- |
| `RESCAN` | A fresh scan was selected as the next decision. | A scan has already run. |
| `EXCEPTION_REQUEST` | A candidate scoped exception proposal should be considered. | The proposal is admitted or active. |
| `STANDARD_CLARIFICATION` | A prospective policy decision is required. | The pinned standard changed. |
| `ABANDON` | The lineage is intentionally ended with rationale. | Findings or history were deleted. |

## Local Git Authority

Orchard binds canonical local Git roots, creates isolated worktrees for dispatched work, and locally fast-forwards accepted candidates. It never pushes a remote in the current product.

Do not edit an Orchard-managed worktree while a run is active. Destination drift or a dirty repository blocks promotion and conformance admission rather than guessing how to merge.

## Planned Governance

The current next milestone adds identity, delegation, quorum, and signed decisions. Verified external policy sources follow it. Until those milestones land, actor and grantor strings are attributable records but not authenticated or cryptographic identity.
