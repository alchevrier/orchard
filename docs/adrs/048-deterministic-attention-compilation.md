# ADR 048: Deterministic Attention Compilation

## Status

Proposed

Extends ADR 028, ADR 029, ADR 046, and ADR 047. It preserves their model-aperture, executable-work-package, authority, correction-lineage, and independent-validation boundaries.

## Context

Orchard compiles admitted intent and design into an `ExecutableWorkPackage` before coding. The durable package contains the requested outcome, current and required behavior, constraints, acceptance criteria, design summary, preserved invariants, non-goals, repository evidence, ownership, operations, sources, checks, unresolved questions, and escalation conditions.

The model-facing coding projection does not preserve that complete structure. It presents selected task fields, paths, operations, expected behavior, and checks while omitting design rationale, constraints, invariants, non-goals, evidence relationships, escalation conditions, scope-to-operation correlations, and operation dependencies. The bounded worker is consequently instructed to treat its work package as complete intent and design authority even though the invocation projection is incomplete.

This is not only a context-size problem. The execution plan already records relationships among accepted scope, evidence, operations, and dependencies, but work-package compilation and model projection flatten or discard some of those relationships. A model must reconstruct why an operation exists, which requirement it advances, what evidence supports it, and what must remain unchanged. Larger models may recover those relationships from adjacent prose and source. Smaller local models are more likely to follow a locally plausible code instruction while losing the governing task outcome.

Work-package execution may also deliberately select one implementation operation from a larger accepted plan. A bounded operation can be a valid resource-aware delivery slice, but the invocation does not explicitly distinguish the active slice from the complete task, identify what remains outside the slice, or state how completion of the slice contributes to the accepted outcome. This makes truncation observationally similar to completeness from the model's perspective.

System prompts, retrieval, and larger model apertures do not close this gap. A prompt supplies general behavior rules, retrieval supplies potentially relevant material, and an aperture controls capacity. None of them deterministically preserves the relationship between the current task, admitted plan, active operation, code owner, and verification contract.

Repeated attempts to correct this behavior through prompt engineering did not produce a reliable coding interface. Additional instructions could tell the model to remain focused, preserve intent, or follow the plan, but they could not restore authority fields and relationships removed before inference. The model was still required to infer the active operation's purpose from a flattened envelope, so prompt changes shifted failure modes rather than eliminating them. This experience is evidence that the defect sits in context compilation, not in the wording of the worker persona.

Attention Compilation therefore establishes an architectural boundary: prompts may explain how a model should use a compiled execution view, but prompts cannot define, reconstruct, or substitute for that view. A model invocation is not adequately focused merely because its system prompt instructs it to focus.

## Decision

Orchard introduces **Attention Compilation**, a deterministic, model-independent stage between admitted execution authority and each model invocation.

The compiler produces a versioned, content-hashed `AttentionFrame` for one workflow step and one pinned authority state. An attention frame is a bounded execution projection, not a new authority source. It may select, order, and correlate admitted information, but it cannot invent requirements, grant actions, broaden ownership, weaken constraints, replace evidence, or declare acceptance.

### Required Relations

Every active implementation operation must retain an explicit, machine-verifiable relationship:

```text
admitted requirement
    -> accepted plan scope
    -> active operation
    -> repository owner and evidence
    -> expected behavior
    -> verification criterion
```

An attention frame records at least:

- the workflow step, task identity, authority revisions, and authority hashes;
- the requested outcome and required behavior relevant to the invocation;
- the active delivery slice and its position within the complete accepted plan;
- active operations and their admitted dependencies;
- requirement, scope, operation, owner, evidence, and verification correlations;
- relevant constraints, preserved invariants, and non-goals;
- exact ownership and allowed-action boundaries;
- unresolved authority or escalation conditions applicable to the slice;
- completion criteria for the active slice; and
- deferred or remaining plan scope, represented as context rather than executable authority.

Stable authority identifiers are used wherever the owning artifact provides them. Until every requirement and criterion has a stable identifier, the frame pins the exact normalized value together with its source artifact identity and hash. Text similarity alone cannot establish a governing relation.

### Deterministic Compilation

The attention compiler consumes only admitted or deterministically derived inputs, including:

- the current work definition and acceptance contract;
- admitted design authority;
- the accepted repository execution plan;
- the executable work package;
- revision-pinned repository evidence;
- current workflow and candidate state; and
- deterministic diagnostics from failed execution or review.

Compilation follows typed identifiers, operation ordering, declared dependencies, scope coverage, ownership paths, evidence citations, and criterion references. Token budgets may reduce optional source excerpts or supporting evidence according to deterministic priority rules, but they cannot remove active requirements, constraints, operation relations, action boundaries, or completion criteria. If mandatory attention does not fit the selected model aperture, execution fails explicitly instead of silently truncating authority.

No LLM generates, repairs, or validates the authoritative relationships in an attention frame. A model may propose upstream analysis or planning artifacts through their existing governed workflows, but only admitted artifacts may enter compilation. Embeddings or learned ranking may later order optional evidence candidates; they cannot create authority relations or remove mandatory content.

### Adequacy and Validation

Before model invocation, an independent deterministic verifier rejects an attention frame when:

- an active operation has no admitted requirement or scope relation;
- a requirement relation has no expected behavior or verification criterion;
- an operation dependency is missing, cyclic, or outside the pinned plan;
- an owner or evidence citation is outside the executable work-package boundary;
- the frame omits an applicable constraint, invariant, non-goal, or escalation condition;
- an active slice is presented as complete while admitted work remains;
- a referenced authority revision or hash is stale or inconsistent; or
- mandatory content exceeds the effective model aperture.

Model output remains subject to the existing bounded-tool, repository, verification, review, acceptance, and promotion controls. Attention adequacy proves only that the model received a coherent execution view; it does not prove that the resulting implementation is correct.

### Lifecycle and Evidence

The exact attention-frame hash is recorded with model-execution provenance. Retries may reuse a frame only while all pinned authority and repository revisions remain valid. A correction, admitted design change, execution-plan revision, ownership change, or relevant repository revision produces a successor frame.

Frames may be persisted directly or reproduced from persisted inputs, provided replay verifies the same format version and content hash. Human-readable conversation and UI focus may project attention state, but neither becomes execution authority.

### Placement

Attention Compilation is implemented inside Orchard alongside work-package compilation and model-envelope construction. Its schema is serialization-friendly and provider-neutral so another Orchard component may consume it later, but it is not initially a separate service or project. Extraction requires a demonstrated independent lifecycle or multiple non-Orchard consumers.

## Consequences

- Local models receive an explicit explanation of how the active code operation serves the admitted task rather than having to reconstruct that relationship from adjacent fields.
- Resource-aware operation slicing becomes visible and distinguishable from complete-task execution.
- Prompt size can be reduced without silently dropping governing intent, constraints, or verification relationships.
- Model behavior becomes easier to diagnose because each invocation pins the exact focus Orchard compiled for it.
- The same authority can produce role-specific attention frames for coding, review, correction, or analysis without granting those roles additional authority.
- Attention schemas, compilers, adequacy checks, provenance, and compatibility rules add implementation and migration cost.
- Existing model-envelope tests that intentionally omit design and invariant fields must be revised to test relevance-preserving compilation rather than indiscriminate prompt reduction.

## Boundaries

- Attention is not human-like cognition, autonomous prioritization, or a claim about transformer internals. It is a governed context-compilation mechanism.
- An attention frame does not replace the work definition, admitted design, execution plan, executable work package, evidence ledger, or acceptance contract.
- Attention does not grant repository, shell, Git, approval, review, merge, push, or promotion authority.
- Optional evidence ranking must remain subordinate to deterministic inclusion rules and pinned authority.
- Source excerpt selection remains a separate mechanism. Attention identifies why source is relevant; excerpting determines which revision-pinned bytes fit within the available budget.
- UI selection state and conversation `ATTENTION` activity severity remain presentation concepts and are not execution attention.
- The first implementation may target the bounded coding worker. Generalization to every model-backed role requires role-specific adequacy rules rather than one universal prompt envelope.

## Alternatives Considered

### Rely on the executable work package alone

Rejected because the durable package contains relevant authority but the current invocation projection removes relationships and governing fields. Possessing authority and presenting a coherent bounded execution view are different responsibilities.

### Increase the model context window

Rejected because capacity does not establish task-to-plan relationships, makes local execution more expensive, and still asks the model to infer which information governs the active operation.

### Express attention only in the system prompt

Rejected because prose instructions cannot recover omitted authority, prove correlation completeness, or distinguish an intentionally active slice from a truncated plan.

### Use an LLM to summarize the task before each invocation

Rejected as the authority mechanism because a generated summary can omit constraints, invent relationships, or reinterpret admitted intent. A model-generated summary may be advisory only when it is clearly separated from the deterministic frame.

### Create an independent Attention service immediately

Rejected because attention compilation shares Orchard's authority schemas, hashes, workflow lifecycle, and failure semantics. A service boundary would add versioning and availability failure modes before an independent lifecycle or external consumer exists.

### Treat retrieval ranking as attention

Rejected because relevance ranking answers which material appears useful, not which admitted requirement authorizes an operation or which criterion proves completion.
