# ADR 020: Analysis-Compiled Execution

## Context

A coding model given a broad ticket and repository context must discover implementation, choose architecture, plan edits, write code, and assess its own result in one invocation. This creates avoidable surprises: duplicate implementations, scaffolding mistaken for behavior, unnecessary new abstractions, and retries caused by decisions that should have been settled before coding.

Orchard already pins workflow context, recalls completed work, reserves Git worktrees, verifies evidence, and audits candidates independently. The missing authority boundary is a repository-aware analysis and design stage that converts requirements into exact execution instructions.

## Decision

Orchard separates repository analysis from coding.

A staffed analyst-designer uses a broad-context local-model profile to inspect bounded, revision-pinned repository evidence. It classifies requested behavior as absent, scaffold-only, partially implemented, implemented in another form, nonconforming, complete, or conflicting. It must prefer reuse or refactoring of the owning path over a parallel implementation when evidence supports that decision.

The model returns candidate analysis. Deterministic Kotlin admits an execution plan only when:

- every citation matches an exact supplied path and content hash;
- existing-path and new-path operations agree with repository shape;
- ordered operations cover the exact accepted criteria;
- verification commands equal admitted workflow commands; and
- no unresolved architecture remains.

Accepted plans are immutable, append-only, and bound to workflow run, project, repository revision, model execution, profile, binding, prompt, context, output, and authority hash. Repository drift preserves the old plan as evidence and requires a successor analysis revision.

The coding worker uses a smaller context profile. Its claim pins the accepted plan ID and hash. Its repository context contains only plan-targeted files, and candidate operations are rejected before mutation when a path or action class exceeds plan authority. The coder may implement bounded syntax and local details, but it does not choose architecture, scope, ownership, or reuse strategy.

## Consequences

- Most repository comprehension and design cost occurs once in broad analysis rather than in every coding retry.
- Smaller code-specialized local models can execute precise plans with less context and fewer architectural surprises.
- Analysis, coding, verification, and audit have distinct model profiles, staffing evidence, and failure outcomes.
- Stale assumptions return to analysis instead of inviting coder improvisation.
- The plan is inspectable in the Architect cockpit and replayable after restart.
- Reliable admission depends on repository evidence quality; bounded lexical retrieval is the current implementation and symbol-aware retrieval remains an improvement path.
- Conflicts and unresolved design questions stop automation for architect judgment.
- Complete dispositions require an explicit verification-only workflow path before they can close work without a source change.