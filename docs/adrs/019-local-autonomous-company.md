# ADR 019: Local Autonomous Company Control

## Context

Guided Product Genesis produces admitted product, experience, architecture, and repository authority. Orchard already has staged plans, durable dispatch, workflow runs, isolated worktrees, governed coding workers, evidence contracts, and local Git bindings. Turning those pieces into a continuously operating local software company requires staffing, architecture-rule compilation, independent judgment, accountability, and promotion state.

Building a second workflow engine for that state would duplicate lifecycle authority and permit the company projection to disagree with delivery truth. Letting models mutate workflow or Git directly would also bypass deterministic validation and evidence gates.

## Decision

Orchard will add a narrow, append-only company-control authority that records only:

- compiled architecture rule sets bound to admitted genesis;
- staff assignments and evidence-based escalation;
- independent architecture and quality audit judgments;
- candidate acceptance; and
- explicit local promotion.

Existing authorities remain canonical:

- `WorkspaceStore` owns hierarchy, workflow runs, transitions, evidence, and model-execution provenance;
- `CodingWorkerService` owns governed candidate execution in reserved worktrees;
- `RepositoryBindingStore` owns repository validation and local fast-forward promotion; and
- Git owns source revisions and diffs.

The company circuit projects phase and accountability from these authorities. Audit models receive a bounded, read-only envelope containing the exact candidate revision, compiled rules, repository context, and objective evidence. A judgment is accepted only when every rule is covered, cited evidence belongs to the current candidate, and the assigned auditor authority matches. Violations reopen the existing run for repair. Acceptance requires both architecture and quality conformance for the same revision, diff, genesis, and rule-set hashes.

Greenfield startup materializes the admitted repository blueprint locally and is idempotent across restart. Promotion is an explicit local operation after acceptance and never performs a remote push.

## Consequences

- Orchard can operate continuously without introducing competing workflow state.
- Staffing and audit decisions are replayable, attributable, and bound to immutable model identities and evidence.
- Stale evidence, stale rule sets, no-op candidates, and cross-revision judgments fail closed.
- A local architect can inspect the full accountability path from intent through promotion.
- Independent audit adds model calls and repair latency before promotion.
- Remote collaboration, pull-request publication, and distributed company coordination remain outside this authority.