# ADR 040: Engineering Standards Authority and Repository Conformance Compiler

## Status

Accepted

## Context

Orchard can govern product architecture, workflow evidence, broad repository analysis, bounded coding, independent audit, and local promotion. It did not have an explicit, adjustable authority for system-wide engineering practices. Inferring practices from the current repository would turn existing defects into policy, while treating ADR text alone as implementation would mistake declarations for working behavior.

Repository improvement also requires interpretation. A model can correlate broad evidence and propose useful remediation, but it must not create authoritative work directly. Standards, architectural decisions, repository bytes, and admitted workflow entities have different ownership and lifecycle semantics.

## Decision

Orchard maintains three independent truths:

1. An engineering standard states what should be true.
2. ADR and design authority state what was decided.
3. revision-pinned repository evidence states what is implemented.

A built-in baseline covers authority integrity, model-output admission, evidence-bound delivery, secret custody, and decision-to-implementation correlation. A project adopts or adjusts this baseline by saving immutable standard revisions. Practices have stable IDs, required or advisory severity, applicability, requirements, required evidence, remediation guidance, and an enabled state.

A full conformance scan requires a clean bound Git `HEAD`. The broad repository-analysis profile receives the exact standard revision and content-hashed tracked files. It must return exactly one finding per enabled practice using one of:

- `CONFORMING`
- `NONCONFORMING`
- `PARTIAL`
- `NOT_APPLICABLE`
- `UNKNOWN`
- `CONFLICTING`
- `EXCEPTION_ACTIVE`

Every substantive judgment cites supplied path and content hash evidence. The backend rejects invented practice IDs, paths, hashes, affected files, verification commands, incomplete required remediation, uncovered actionable findings, oversized proposals, and repository drift.

The model may compile actionable findings into one candidate `Epic -> Story -> Task/Bug/Investigation` hierarchy. That hierarchy remains part of the immutable scan and cannot mutate workspace authority. An explicit admission command rechecks the clean repository revision and workspace capacity, then creates the entire hierarchy through one `WorkspaceStore` batch. Investigations map to Tasks while preserving their semantic type in the generated content.

Standards, scans, and admissions are separate checksummed append-only records. A standard revision never changes an old scan, and admission never mutates the scan. Generated entities carry the immutable scan hash. If workspace commit succeeds but admission journaling fails, a retry reconciles the exact marked entity set and appends the missing admission without creating duplicate work.

## Consequences

- Users can change engineering policy without rewriting history.
- Orchard can inspect and improve its own repository by binding its project to the Orchard checkout.
- ADR claims, implementation, tests, and configuration can disagree without one source silently overwriting another.
- Broad local models perform cross-repository correlation while smaller coding models receive admitted bounded work.
- Conformance scanning is read-only; workspace entity count changes only after explicit admission.
- Every finding and proposed remediation retains standard, repository, model binding, prompt, context, output, and timestamp provenance.
- Conflicts and insufficient evidence become investigations rather than guessed implementation tasks.

## Boundaries

- The first implementation supports immutable project-level standards plus a built-in baseline. Organization, module, and explicit exception overlays are represented by the model but remain future authority layers.
- Repository evidence uses bounded ranked tracked-file context rather than complete semantic indexing or language-server symbol graphs.
- The built-in baseline is intentionally small. Additional practices should arrive as versioned authority, not hard-coded scanner behavior.
- Workspace admission remains subject to the current 32-entity capacity.
- A model generation failure does not mutate standards, scans, repositories, or workspace entities.
- The scanner proposes verification commands from the detected build system; it does not execute them during conformance classification.

## Alternatives Considered

### Infer standards from the repository

Rejected because prevalent implementation patterns may be accidental, obsolete, or defective. Existing behavior is evidence, not policy.

### Treat ADRs as conformance proof

Rejected because documentation can be stale or scaffold-only. Decisions must correlate with source, tests, configuration, and runtime wiring.

### Let the model create backlog entities directly

Rejected because interpretive output is untrusted candidate data. Deterministic validation and explicit admission must remain separate.

### Regenerate the backlog whenever standards change

Rejected because it destroys provenance and can rewrite active work. New standards produce new scans and candidate proposals while prior authority remains immutable.
