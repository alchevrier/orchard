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

Engineering standards are immutable project revisions with stable practice IDs. A scan judges every enabled practice against a clean repository revision and records exact citations.

`UNKNOWN` means evidence is insufficient. `CONFLICTING` means supplied evidence supports incompatible conclusions. Neither should be converted into confident implementation work without investigation or resolution.

`EXCEPTION_ACTIVE` is recognized as a resolved disposition, but active scoped exception authority is not implemented at Milestone 10.0. A model or user cannot truthfully create this disposition merely by requesting an exception. Milestone 10.1 on the [Roadmap](../../ROADMAP.md) introduces that authority.

## Campaign Closure

A remediation campaign closes only when a promoted follow-up scan proves every linked practice is resolved and no seeded practice regressed.

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
| `EXCEPTION_REQUEST` | An exception should be considered by a future authority. | An exception is active. |
| `STANDARD_CLARIFICATION` | A prospective policy decision is required. | The pinned standard changed. |
| `ABANDON` | The lineage is intentionally ended with rationale. | Findings or history were deleted. |

## Local Git Authority

Orchard binds canonical local Git roots, creates isolated worktrees for dispatched work, and locally fast-forwards accepted candidates. It never pushes a remote in the current product.

Do not edit an Orchard-managed worktree while a run is active. Destination drift or a dirty repository blocks promotion and conformance admission rather than guessing how to merge.

## Planned Governance

The current next milestone adds scoped standards overlays and exception authority. Identity, delegation, quorum, signed decisions, and verified external policy sources follow it. Until those milestones land, actor strings are attributable records but not cryptographic identity.
