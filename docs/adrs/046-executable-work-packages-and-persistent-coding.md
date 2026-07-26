# ADR 046: Executable Work Packages and Persistent Coding

## Status

Accepted

Supersedes the one-shot proposal and failed-candidate reversion portions of ADR 036. Its repository, process, evidence, audit, acceptance, and promotion boundaries remain in force.

Accepted after the controlled `qwen3-coder:30b` replay demonstrated bounded implementation and same-lineage repair with a materially smaller model. That replay was supervised and promoted outside production Orchard; Milestone 10.2.2 separately governs the first fully Orchard-executed delivery proof.

## Context

The Milestone 10.2 Orchard-on-Orchard typography experiment showed that deterministic governance rejects invalid work correctly, but the coding interface is not viable. A model had to rediscover implementation details while serializing complete exact-text replacements. Local compile and test defects caused candidate reversion and repository reanalysis instead of repair on the same branch.

The failure persisted with a 120B coding binding. More context and more exact-anchor diagnostics therefore cannot be the baseline architecture, especially when Orchard must support materially smaller local models.

## Decision

Accepted intent and design compile into a versioned, content-hashed `ExecutableWorkPackage` before coding begins. The package pins:

- work-definition and execution-plan identities and hashes;
- repository revision;
- expected behavior, constraints, invariants, and non-goals;
- an explicit ownership boundary and advisory likely implementation paths;
- allowed coding actions;
- revision-bound editable source and relevant declarations;
- named verification checks; and
- conditions that require design escalation.

An independent deterministic adequacy verifier rejects a package before coding when behavior or verification is absent, authority is malformed, source bytes are missing or stale, likely paths exceed ownership, constraints contradict non-goals, or escalation policy is incomplete. Adequacy means a competent bounded coder can act without choosing architecture or rediscovering intent. It does not mean the implementation is correct.

The coding worker will consume the adequate package through Orchard-owned bounded tools rather than emit one complete patch document. It may discover the final changed paths inside the ownership boundary. Repository mutation remains transactional and fails closed outside that boundary.

A candidate branch persists across implementation, compilation, focused tests, and local repair. Verification failures append diagnostics and checkpoints to the same candidate lineage. Reanalysis is reserved for stale authority, missing scope, contradictory design, or another explicit escalation condition. Reversion occurs only when the package is abandoned, superseded, or invalidated.

After checks pass, Orchard freezes a local candidate PR artifact containing the actual diff, implementation claims, checks, evidence, and deviations. Independent review separately decides whether the code supports its claims and whether it satisfies admitted intent and design. Existing audit, acceptance, and controlled promotion remain downstream authorities.

## Consequences

- Design-to-coding handoff becomes explicit, versioned, and testable.
- The coder can repair ordinary implementation defects without organization-wide replanning.
- Likely design paths guide discovery without pretending they are the final diff.
- Smaller models receive a bounded engineering task and stable tools instead of a fragile patch-serialization problem.
- Work-package compilation, tool execution, candidate persistence, and PR review can be delivered and measured independently.

## Boundaries

- The initial compiler uses accepted work definitions, execution plans, and repository context already produced by Orchard.
- Version 1 requires full source for existing bounded paths; symbol-scoped source is deferred until stable symbol identity exists.
- Package adequacy does not execute builds or prove semantic correctness.
- This decision does not grant the model shell, Git, evidence, approval, merge, push, or promotion authority.
- ADR 036 remains authoritative until each replacement runtime slice is implemented and validated.