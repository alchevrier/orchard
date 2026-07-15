# ADR 022: Task, Role, Agent, and Model Routing

## Status
Proposed

If accepted, this ADR supersedes ADRs 007 and 016. It partially supersedes agent assignment and execution decisions in ADRs 002, 004, 008, 009, 014, and 015.

## Context
Different engineering tasks require different responsibilities, permissions, context sizes, tools, modalities, costs, and reasoning capabilities. A Kotlin refactor, C++ benchmark investigation, architecture review, repository discovery pass, and documentation update should not automatically use the same agent behavior or model.

Binding a task directly to a model conflates organizational responsibility with an inference implementation. Binding a role permanently to one provider prevents local or remote selection, explicit user preference, fallback behavior, and evidence-based improvement. Allowing a model to select itself makes routing unauditable.

Orchard needs explicit task ownership and predictable model preferences while retaining the ability to recommend better selections from observed outcomes.

## Decision
Orchard separates four concepts:

1. **Task**: the governed unit of desired work, requirements, scope, risk, constraints, and acceptance criteria.
2. **Role**: responsibilities, workflows, permissions, required capabilities, and evidence obligations.
3. **Agent run**: an immutable execution assignment that binds one task, role, resolved workflow, context manifest, permissions, and selected model for a bounded invocation.
4. **Model**: a replaceable inference resource exposed by a provider and described by capabilities, limits, locality, cost, and observed outcomes.

A task may require multiple role assignments, such as architecture, implementation, quality, and review. High-risk policies may require different agent runs or model families for implementation and review. A reviewer reports findings by default and cannot silently assume implementation authority.

Roles are model-independent and filesystem-defined. A role declares:

- responsibilities and prohibited actions;
- applicable workflow families;
- required tools, modalities, and capabilities;
- read, edit, execute, commit, publish, and external-access permissions;
- required outputs and evidence;
- escalation and approval boundaries.

Model profiles record declared facts and measured evidence separately. Declared facts include provider, locality, context limit, modalities, tool support, availability, and pricing. Outcome evidence includes task type, role, project or language scope, validation results, repair iterations, review findings, user corrections, latency, token use, cost, later regressions, and final disposition. Self-reported model success is not authoritative outcome evidence.

Users may define model preferences by task type, role, language, domain, project, and narrower scope. Preference resolution uses this precedence:

1. explicit per-task override;
2. project task-type and language/domain preference;
3. project task-type preference;
4. project role preference;
5. shared task-type and language/domain preference;
6. shared task-type preference;
7. shared role preference;
8. evidence-based automatic ranking;
9. configured default and fallbacks.

Preferences support three modes:

- **strict**: use the selected model or fail visibly;
- **prefer**: try ordered preferences, then eligible fallbacks;
- **automatic**: rank eligible models using policy and scoped outcome evidence.

Constraints are evaluated before preferences. A model is ineligible when it violates privacy, locality, permission, context, modality, tool, availability, or cost constraints. The router records why candidates were excluded and why the winner was selected.

Preferences are user authority. Outcome evidence may produce a recommendation to change a preference, but Orchard does not silently rewrite it. Recommendations enter the intelligence lifecycle from ADR 020 as candidates with supporting and contradicting evidence.

Every agent run records:

- task, role, workflow, and model versions;
- resolved context manifest and intelligence sources;
- permissions and applied constraints;
- preference source and selection mode;
- considered fallbacks and routing explanation;
- produced artifacts, evidence, costs, and outcome;
- retries, handoffs, corrections, and review disposition.

Model routing is an Orchard policy decision. Models and agents cannot promote their own output, alter approved practices, expand permissions, or mark evidence gates satisfied without independently verifiable records.

## Consequences
- The same role can use a local model for private or inexpensive work and a stronger remote model for complex work without changing workflow semantics.
- Explicit preferences make routing predictable while evidence-based recommendations allow improvement over time.
- Orchard requires provider-neutral model adapters, a model registry, role definitions, assignment records, and routing explanations.
- Outcome comparisons must be scoped by task, role, language, project, and risk; a universal model leaderboard would be misleading.
- Independent review can reduce correlated failure but increases latency and cost.
- Unavailable strict preferences fail clearly rather than silently selecting a different model.
- Historical routing and outcome records may contain sensitive project and cost data and must follow project privacy policy.
- This ADR defines routing semantics and authority, not initial roles, supported providers, or implementation priority.