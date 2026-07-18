# Product Workflow

This guide follows one product from first intent through governed delivery and repository conformance.

## 1. Product Genesis

The guided flow advances through fixed phases:

```text
CLASSIFICATION -> EXPERIENCE -> ARCHITECTURE -> BLUEPRINT -> ADMISSION -> READY
```

### Classification

Choose the product context:

- **Greenfield local**: Orchard will materialize the admitted repository blueprint locally.
- **Existing local**: bind an existing clean Git repository.
- **Organization governed**: visible for architectural planning, but admission remains blocked until verified organizational policy sources exist.

### Experience

Define the audience, product promise, primary journey, interaction principles, emotional qualities, exclusions, and accessibility commitments. Select the first Epic as a vertical slice that proves the experience.

### Architecture

Define components and decisions correlated to requirements, dependencies, and repository paths. Orchard stores candidate revisions with hashes and rejects stale updates.

### Blueprint

Declare the repository root, toolchain, modules, verification commands, and policy packs. Greenfield materialization uses this admitted blueprint; existing repositories retain their bound Git root.

### Admission

Review the complete revision. Admission is explicit and cannot be inferred from a conversation or model proposal. Production workflow starts remain blocked until genesis reaches READY.

## 2. Start the Local Company

From READY, select **Start local company circuit**. Orchard then coordinates existing authorities rather than giving one agent unrestricted control.

The company circuit:

1. compiles admitted architecture into audit rules;
2. plans the first governed delivery slice;
3. assigns compatible local or permitted remote model bindings to roles;
4. performs broad repository analysis;
5. compiles a revision-pinned execution plan;
6. dispatches bounded work into an isolated Git worktree;
7. applies typed coding operations;
8. runs admitted verification commands;
9. performs independent architecture and quality audits; and
10. records acceptance or routes failures back for repair.

Refresh the cockpit to inspect current phase, staffing, execution plans, evidence, audits, and required decisions. Background dispatch, analysis, coding, audit, and campaign reconcilers run once per second while the backend is active.

## 3. Promote Accepted Work

When a run has accepted evidence and independent audits, the cockpit offers **Promote accepted candidate locally**.

Promotion:

- verifies the candidate descends from the expected local base;
- verifies the canonical diff hash;
- requires a clean destination repository;
- performs a local fast-forward; and
- records the resulting revision.

Orchard does not push a remote. Review and publish the local repository using your normal Git process outside Orchard.

## 4. Define Engineering Standards

The Engineering Standards projection starts from a built-in baseline. You can adjust practices and save a project standard revision.

A practice declares:

- stable practice ID;
- title and category;
- required or advisory severity;
- applicability;
- requirement and required evidence;
- remediation guidance; and
- whether it is enabled.

Saving creates a new immutable standard revision. It does not rewrite previous scans or campaigns.

## 5. Scan Repository Conformance

Select the conformance scan action only when the bound repository has a clean HEAD.

The broad-analysis model receives bounded tracked repository evidence and must return exactly one disposition for each enabled practice:

- `CONFORMING`
- `NONCONFORMING`
- `PARTIAL`
- `NOT_APPLICABLE`
- `UNKNOWN`
- `CONFLICTING`
- `EXCEPTION_ACTIVE`

Findings cite exact paths and content hashes. The model may propose an Epic, Stories, and Task/Bug/Investigation leaves, but scanning does not mutate the workspace.

Review findings, citations, acceptance criteria, commands, and proposed hierarchy. Select backlog admission only when you accept that exact candidate work at that exact repository revision.

## 6. Follow Remediation Campaigns

Backlog admission opens a durable remediation campaign. Orchard compiles and executes one leaf at a time through the same definition, design, analysis, coding, verification, audit, acceptance, and promotion authorities.

Campaign states:

| State | Meaning |
| --- | --- |
| `ADMITTED` | Candidate work is admitted but has not started. |
| `IN_PROGRESS` | A bounded slice is executing or more admitted leaves remain. |
| `VERIFYING` | Accepted work is waiting for promotion or follow-up evidence. |
| `CLOSED` | A promoted follow-up scan resolves every linked practice without regression. |
| `BLOCKED` | All admitted leaves are exhausted and linked practices remain unresolved. |
| `ESCALATED` | Evidence is unknown or conflicting, or a resolved seeded practice regressed. |

Task completion does not close a campaign. Closure requires a new scan of the exact promoted revision against the campaign's pinned standard revision.

## 7. Resolve Terminal Campaigns

A `BLOCKED` or `ESCALATED` evaluation opens one resolution case. The case records exhaustion, conflict, uncertainty, or regression and pins the exact terminal evidence.

Select **Ask Architect for resolution** to create a candidate action. Review its rationale and instructions before selecting **Admit resolution decision**.

Possible actions are:

- additional remediation;
- investigation;
- rescan request;
- exception request;
- standard clarification; or
- abandonment.

Additional remediation and investigation can create a new admitted hierarchy and linked successor campaign. The other actions currently record a durable decision only. They do not automatically run a scan, grant an exception, or modify a standard.

## Operating Rule

When a control is unavailable, inspect the projected state and diagnostic. Do not modify `~/.orchard` records to force a transition; those files are checksummed authority and manual edits can make startup fail closed.
