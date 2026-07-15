# ADR 021: Derived Practices and Executable Workflows

## Status
Proposed

If accepted, this ADR supersedes ADR 008. It partially supersedes the governance mechanisms in ADRs 002, 004, 009, and 014.

## Context
Engineering work depends on more than a ticket description. Correct execution requires project-specific knowledge about architecture, concurrency, ownership, testing, benchmarking, refactoring, review, release, security, and evidence. These practices may be documented explicitly, encoded in CI and build files, repeated in code and tests, or discovered through successful and failed work.

A static global prompt cannot represent this accurately. Copying a complete workflow for every combination of task, language, domain, and project would create duplication and drift. Treating common code as mandatory policy would also mistake legacy behavior or accidental convention for intent.

Orchard therefore needs to read repositories and documents, distinguish observation from inference, derive candidate engineering models and practices, and compose approved knowledge into task-specific executable workflows.

## Decision
Orchard models governance as a layered derivation:

```text
source evidence -> observations -> inferences -> approved practices -> resolved workflows
```

Repository discovery may inspect:

- repository instructions and manifests;
- build and dependency configuration;
- CI and release automation;
- ADRs and architecture documents;
- representative production boundaries;
- neighboring tests and fixtures;
- benchmark configuration and reports;
- source history and recurring review findings;
- outcomes and corrections from previous Orchard tasks.

Discovery is incremental and evidence-directed. Orchard does not treat an indiscriminate repository dump as analysis. Each observation references the exact source revision and excerpt that supports it. When documentation, executable configuration, and code disagree, Orchard records the contradiction and proposed scope instead of silently choosing one.

Candidate engineering models include, but are not limited to:

- concurrency, cancellation, and ownership;
- mutability and state publication;
- error handling and retry policy;
- memory and allocation constraints;
- persistence and transaction boundaries;
- API and compatibility policy;
- testing and test-data strategy;
- benchmarking and performance evidence;
- refactoring and anti-pattern guidance;
- code, architecture, security, and release review.

Workflows are composed rather than duplicated. A resolved workflow combines:

1. a task workflow, such as implement feature, fix defect, refactor, optimize, benchmark, review, migrate, release, or investigate incident;
2. language and framework profiles;
3. domain profiles;
4. approved project practices and policies;
5. repository-specific commands and quality gates;
6. explicit task requirements and user overrides.

Project-scoped approved practices override shared defaults within their declared scope. More specific module or task policy overrides broader project policy. Orchard presents the resolved workflow and its source records so behavior is explainable.

Every executable workflow declares:

- applicability conditions and required inputs;
- ordered steps and ownership;
- required tools or capabilities;
- permissions and approval boundaries;
- expected artifacts and evidence;
- gates that prevent invalid transitions;
- failure, retry, escalation, and rollback behavior;
- references to the practices and observations from which it was derived.

Workflow definitions do not select a concrete model or provider. They declare roles, capabilities, context, evidence, and constraints. ADR 022 resolves those requirements to agent runs and models.

Completed work feeds the intelligence lifecycle. Validation failures, review findings, stale commands, user corrections, successful procedures, and regressions create candidate observations or practice changes. They do not silently rewrite approved workflows. Promotion requires deterministic evidence, explicit review, or a separately approved policy governing automatic acceptance.

The evidence-producing principle from ADR 004 remains: work cannot be considered complete when its required evidence gates are unmet. Evidence requirements are task-specific rather than a universal mandatory phase or fixed coverage percentage.

## Consequences
- Orchard can adapt its execution model to each repository without imposing one universal programming style.
- Derived workflows remain auditable because every rule and step links back to scoped intelligence and evidence.
- Repository discovery requires parsers, source selection, contradiction handling, and review interfaces; LLM extraction alone is insufficient authority.
- Users must be able to approve, reject, narrow, supersede, and explain candidate practices.
- Workflow schemas require versioning because changing a procedure can change execution and evidence expectations.
- Shared workflows remain reusable while project overlays avoid duplication.
- The system must distinguish obsolete practice, local exception, accidental convention, and true contradiction.
- This ADR defines the governance model but intentionally does not prioritize workflow families or discovery capabilities.