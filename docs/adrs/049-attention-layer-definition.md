# ADR 049: Attention Layer Definition

## Status

Proposed

Extends ADR 048 and preserves the authority, execution-plan, work-package, evidence, acceptance, security, operational, and promotion boundaries established by ADRs 004, 012, 024, 028, 029, 034, 035, 046, and 047.

## Context

ADR 048 establishes that Orchard needs deterministic Attention Compilation because prompt engineering cannot restore relationships removed from a model-facing context projection. Orchard may already know the objective, requirements, design, repository evidence, ownership, operations, verification methods, and acceptance criteria, while a local model receives only a flattened subset of that information.

The initial Attention prototype demonstrates task-to-scope-to-operation-to-owner correlations, deferred slices, action-level drift checks, and frame provenance. It does not yet define the complete Attention layer. Without that definition, additional implementation could accumulate fields without establishing which relationships are authoritative, which transitions are allowed, or how Orchard distinguishes a plan, a code change, a verification method, verification evidence, and acceptance.

The goal is not to make a model generally intelligent or to force it through a fixed textual script. The goal is to ensure that a local model working on a specific Orchard objective cannot move astray from the admitted objective and its codebase relationships, including when the objective is transversal across implementation, persistence, API, user interface, migration, operations, security, documentation, and verification.

A further boundary is required because a model can produce plausible but invalid claims. A plan does not prove implementation. An implementation claim does not prove verification. A statement that something is verified is not verification evidence unless an admitted evidence-producing method ran against the resulting repository revision.

## Decision

Orchard defines the **Attention Layer** as its deterministic authority-graph compilation and enforcement layer for model-backed work.

This is Orchard's governing way of conducting model-backed work. The authority graph determines what the work is, what is related to it, what is permitted, what remains incomplete, and what evidence is required. Within those boundaries, the implementation is intentionally open: Orchard may choose different graph representations, compilation strategies, model prompts, tool interfaces, persistence arrangements, and correction workflows as the system evolves. Those choices are valid only when they preserve the Attention invariants and do not make the model responsible for reconstructing or self-authorizing the graph.

The Attention Layer transforms admitted objective authority and its derived execution state into a bounded, invocation-specific graph. That graph is consumed by a model, checked before and after model activity, and recorded for provenance. It tells the model:

- what objective it is advancing;
- which requirements and scope clauses govern the invocation;
- which repository owners and evidence are related to those requirements;
- which operation or slice is active;
- which paths and action classes are authorized;
- what behavior and invariants must be preserved;
- what work is deferred or remains incomplete; and
- which verification methods can produce evidence for the relevant claims.

The Attention Layer does not become a new source of requirements or permissions. It compiles and enforces relationships already admitted by Orchard authority.

The enduring policy is therefore:

> **Orchard decides and exposes the relationships; the model is free to reason and implement within them; Orchard verifies that it stayed within them.**

The graph is the invariant. Any particular `AttentionFrame` schema, compiler, prompt format, provider adapter, or worker integration is an implementation that may be replaced without changing that policy.

### Attention Graph

The canonical graph contains typed nodes:

```text
OBJECTIVE
  -> REQUIREMENT
    -> SCOPE
      -> DESIGN
        -> EVIDENCE or OWNER
          -> OPERATION
            -> CANDIDATE_CHANGE
              -> VERIFICATION_METHOD
                -> VERIFICATION_EVIDENCE
                  -> ACCEPTANCE
```

A transversal objective may branch into multiple scope dimensions:

```text
OBJECTIVE
  -> IMPLEMENTATION
  -> PERSISTENCE
  -> API
  -> USER_INTERFACE
  -> MIGRATION
  -> VERIFICATION
  -> DOCUMENTATION
  -> OPERATIONAL
  -> SECURITY
```

These nodes are not all executable. In particular:

- `EVIDENCE` explains why an owner or scope is relevant;
- `OWNER` identifies the responsible repository or operational boundary;
- `OPERATION` grants a bounded change or verification action;
- `CANDIDATE_CHANGE` records what actually changed;
- `VERIFICATION_METHOD` describes how evidence may be produced;
- `VERIFICATION_EVIDENCE` records the result of executing that method; and
- `ACCEPTANCE` is a downstream authority that consumes evidence and judgments.

The canonical edge types are:

```text
REQUIRES
SPLITS_INTO
DESIGNED_BY
JUSTIFIED_BY
OWNED_BY
ALLOWS_ACTION
DEPENDS_ON
IMPLEMENTED_BY
VERIFIED_BY
PRODUCES_EVIDENCE_FOR
ACCEPTED_BY
```

Every actionable edge must point to admitted authority. A model-generated relationship is a proposal or diagnostic until Orchard deterministically admits it through the owning workflow.

### Definition of Focus

A model invocation is focused when both traceability directions hold for its active graph:

**Forward traceability**

```text
objective -> requirement -> scope -> owner/evidence -> operation -> verification
```

Every active requirement or scope clause must reach either:

- an authorized implementation operation and its verification method; or
- an explicit evidence-only disposition with its verification method.

**Reverse traceability**

```text
candidate operation -> accepted operation -> owner/evidence -> scope -> requirement -> objective
```

Every actionable path, operation, and mutation class presented to or returned by the model must trace backward to an admitted requirement. An operation that is technically sensible but has no reverse trace is drift and is rejected.

Attention is inadequate when either direction contains an orphan:

- an orphan requirement has no implementation or evidence-only disposition;
- an orphan actionable operation has no admitted requirement origin;
- an orphan path has no owning authority;
- an orphan verification claim has no evidence-producing method; or
- an orphan evidence record has no criterion and candidate revision.

### Authority States

The Attention Layer preserves these state boundaries:

```text
PLANNED
  -> ADMITTED
    -> IMPLEMENTED
      -> VERIFICATION_AVAILABLE
        -> EVIDENCE_PRODUCED
          -> ACCEPTED
```

The model may help produce a proposed plan, implementation, or correction. It may not skip states or author their authoritative transitions.

In particular:

- `PLANNED` means an intended change exists;
- `ADMITTED` means Orchard accepted the plan and permissions;
- `IMPLEMENTED` means a candidate contains a change tied to an authorized operation;
- `VERIFICATION_AVAILABLE` means an admitted method exists, not that it passed;
- `EVIDENCE_PRODUCED` means the method ran against the candidate revision and emitted valid evidence; and
- `ACCEPTED` means the downstream acceptance authority accepted the required evidence and judgments.

A model summary cannot promote a node between these states. For example, the following is not evidence:

```text
"Backward compatibility has been verified."
```

Evidence requires a recorded method, command or typed runner, target repository revision, outcome, and output or observation hash according to the applicable evidence contract.

### Attention Frame Contract

An `AttentionFrame` is the serialized invocation projection of the graph. Version 1 must contain:

- objective and work-item identity;
- source authority revisions and hashes;
- workflow step and model-role identity;
- active, prerequisite, and deferred slices;
- typed requirement, scope, owner, evidence, operation, and verification references;
- path-specific allowed actions;
- relevant constraints, invariants, non-goals, and security or operational conditions;
- expected behavior and completion conditions;
- escalation conditions; and
- a canonical content hash.

Full source bytes remain in the revision-pinned repository context. The frame carries why a source path is relevant and what may happen to it; it does not duplicate large source content.

The frame must distinguish executable authority from descriptive context. Deferred work may be shown so the model understands the complete objective, but deferred nodes do not grant actions. Evidence-only nodes may explain compliant owners, but they do not grant mutation authority.

### Deterministic Compilation and Repair

The compiler may automatically repair only derived or mechanical defects, such as:

- canonical ordering and deduplication;
- resolving an unambiguous admitted operation order to its owner;
- deriving bounded tool actions from an admitted plan action;
- rebuilding a missing projection field from pinned authority; and
- reducing optional supporting evidence to fit the model aperture.

The compiler must reject or escalate when repair would require guessing:

- which requirement owns a path;
- whether a new owner is relevant;
- whether a security or operational impact exists;
- whether an operation should be CREATE, MODIFY, or DELETE;
- whether a claim is verified; or
- whether an omitted scope is intentionally complete.

A failed model proposal may receive a deterministic diagnostic and a bounded retry. Retry does not change the graph. It produces a successor candidate or an escalation when the graph itself is inadequate.

### Transversal and Impact Scope

Attention does not equate focus with a single file or narrow scope. For a transversal objective, deterministic impact analysis identifies applicable scope dimensions and their owners.

Operational scope is required when the objective affects deployment, configuration, persistence lifecycle, resource behavior, observability, restart, recovery, or rollback.

Security scope is required when the objective affects identity, roles, ACLs, permissions, authentication, authorization, privilege, tenancy, data visibility, secrets, audit, or delegation.

When triggered, both dimensions participate in forward and reverse traceability. For an ACL change, an adequate graph must connect authorization requirements to the authoritative policy owner, every enforcement point, fail-closed denial behavior, audit production, and positive and negative verification. The model cannot decide that security or operational impact is irrelevant.

A transversal slice may execute only part of the graph, but it must carry its parent objective identity, active correlations, prerequisites, deferred correlations, and completion state. A slice cannot claim objective completion while required correlated work remains.

### Enforcement

The Attention Layer enforces the graph at four boundaries:

1. **Before inference:** verify frame identity, hashes, relations, ownership, actions, mandatory authority, slice state, and model-aperture fit.
2. **During inference:** provide the frame and revision-pinned source context; do not ask a prompt to reconstruct omitted relationships.
3. **Before mutation:** reverse-check every returned path and action against an actionable frame correlation and the bounded work-package authority.
4. **Before acceptance:** require candidate, method, revision, and evidence relations; reject unsupported implementation or verification claims.

Attention adequacy does not prove semantic correctness. It proves that the model received and used a coherent authority graph. Existing compilation, tests, security checks, evidence gates, review, acceptance, and promotion remain authoritative.

### Persistence and Provenance

An attention frame may be persisted directly or deterministically reproduced from its pinned inputs. In either case, Orchard records its format version and canonical hash with model execution provenance.

A retry may reuse a frame only while all relevant authority and repository revisions remain valid. Changes to requirements, design, plan, ownership, security or operational impact, candidate base, or relevant source revision require a successor frame.

Conversation and UI surfaces may display the current attention state as a projection. They cannot mutate the graph or substitute for its authority.

## Consequences

- Orchard has a precise definition of focus: bidirectional traceability through an admitted authority graph.
- Local models can work on small or transversal objectives without being asked to reconstruct the task structure from a flattened prompt.
- A plausible but unrelated code change becomes a detectable orphan operation rather than an implicit model decision.
- Planning, implementation, verification, evidence, and acceptance claims remain separate and auditable.
- Security and operational impact become explicit parts of attention when their triggers are present.
- Deterministic repair can remove mechanical projection errors while ambiguity fails closed instead of becoming model guesswork.
- Attention can be measured by coverage, orphan count, unauthorized action count, unsupported claim count, evidence completeness, and objective alignment.
- The layer adds graph schemas, identity requirements, compiler rules, replay checks, compatibility behavior, and additional evidence storage costs.

## Boundaries

- Attention is not transformer attention, human cognition, autonomous prioritization, or a guarantee of model correctness.
- Attention does not create requirements, approve designs, authorize access, implement code, run tests, produce evidence, accept candidates, merge changes, or promote releases.
- The model remains a bounded proposer and implementer within admitted operations.
- Source retrieval and excerpt selection remain separate mechanisms; attention determines relevance and authority, while retrieval determines which pinned bytes fit.
- Learned ranking, embeddings, or model-generated summaries may assist optional context selection but cannot establish authority edges.
- A frame does not replace work definitions, designs, execution plans, work packages, evidence contracts, security policy, or acceptance gates.
- The first implementation targets the bounded coding worker. Other roles require role-specific graph projections and adequacy rules.

## Alternatives Considered

### Keep Attention as a prompt convention

Rejected because wording cannot restore omitted relationships or enforce reverse traceability.

### Define Attention as a retrieval or ranking system

Rejected because relevance ranking cannot establish which requirement authorizes a path or which method proves a criterion.

### Let the model maintain and repair its own graph

Rejected because a model cannot be the authority for its own permissions, scope, verification, or acceptance transitions.

### Treat implementation claims as verification

Rejected because a candidate change does not demonstrate that the required behavior works, and unsupported claims are not evidence.

### Use one rigid linear execution script

Rejected because valid objectives can be transversal, branch across multiple owners, and require different operations while retaining the same traceability invariants.

### Add security and operations as optional model-selected metadata

Rejected because impact classification affects authority and cannot depend on whether the model remembers to mention it.
