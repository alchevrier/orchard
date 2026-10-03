# ADR 056: Quality-Governed Model Context Compilation

## Status

Proposed

Refines the budgeting boundary in [ADR 028](028-context-bounded-model-profiles.md) and extends [ADR 029](029-user-configurable-model-apertures.md), [ADR 048](048-deterministic-attention-compilation.md), [ADR 049](049-attention-layer-definition.md), [ADR 051](051-resource-bounded-persistence-and-stop-authority.md), [ADR 054](054-commit-pinned-repository-intelligence-manifests.md), and [ADR 055](055-coordinate-aware-work-definition-admission.md). It does not supersede their authority, provenance, resource, or historical-record invariants. Requirements below are architectural policy proposed for implementation, not a claim that the current compiler satisfies them.

## Context

A context window is a finite reasoning resource, not a repository inventory or a transcript archive. Increasing its capacity does not make irrelevant, contradictory, or semantically empty input useful. Conversely, precise repository coordinates establish location and provenance but do not by themselves prove that the model receives enough evidence to reason about behavior.

Orchard must distinguish three questions:

1. What scope and actions are admitted?
2. What evidence is relevant and sufficient for this invocation's uncertainty?
3. Can the complete, useful invocation fit the effective resource aperture?

Collapsing these questions into a file count or a generic overflow diagnostic makes both admission and recovery unreliable.

### Observed Evidence

A read-only audit on 2026-10-03 examined blocked repository-analysis attempt 646 for run 34, pinned to revision `5e23bce`:

| Observation | Result |
| --- | --- |
| Effective profile input aperture | 24,000 tokens |
| Analysis compiler allowance | 70% of the aperture: 16,800 |
| Unit used by the preflight estimator | UTF-8 bytes, returned as a token estimate |
| Recorded selected files | 24, with 23 required paths represented |
| Recorded source excerpt payload | 3,072 bytes; 128 bytes per file |
| Recalled episodes | None |
| Selected full-file hashes checked against pinned Git blobs | 24 of 24 matched |
| Reconstructed prompt | 23,876 bytes; approximately 6,054 content tokens |
| Synthetic prompt with one byte per required source excerpt | 20,517 bytes; approximately 5,309 content tokens |

Token measurements used offline `o200k_harmony` tokenization of a reconstructed envelope with pinned file prefixes and no matched declarations. They exclude provider chat wrappers and are not a captured historical prompt or an exact reproduction of its excerpt text. No model invocation or retry authority was consumed by the audit.

Required paths appeared in three inventories and source hashes in two. Twelve of the 23 coordinates were attached only to a verification/promotion scope clause. Reconstructed prefixes contained package/import preambles rather than declarations. The system prompt requested an analysis-candidate schema while the envelope advertised an execution-plan schema; path-limit wording also conflicted.

This evidence does not establish physical model-window exhaustion or that 23 files intrinsically require slicing. It establishes inaccurate capacity semantics, unnecessary representation cost, insufficient observability, and a risk that structural coverage is mistaken for useful evidence.

ADR 028 deliberately permitted a conservative byte-based preflight estimate when a matching tokenizer was unavailable. That fail-safe was intended to prevent undercounting, not to imply that bytes were measured tokens. Its rationale remains valid; its units, uncertainty, and rejection semantics must become explicit.

## Decision

Orchard will compile **quality-governed invocation context** from admitted authority and revision-pinned evidence. Quality is determined before inference by relevance, authority, semantic adequacy, consistency, representation cost, and reproducibility. It is not a single aggregate score or a model's assertion that it has enough context.

The governing rule is:

> Every context item must explain why it is present, what decision it supports, what authority it carries, and what can safely be omitted or deferred. Fit is evaluated only after the required context is shown to be useful and coherent.

### Context Components and Their Rationale

| Component | Why it belongs | Boundary |
| --- | --- | --- |
| Objective, active requirement, and current state | Identifies the admitted uncertainty to resolve | Does not replay the whole workflow or conversation |
| Constraints, invariants, and non-goals | Prevents a locally plausible answer from violating authority | Preserves mandatory meaning without repeating the same prose |
| Coordinate and ownership relationships | Explains where reasoning starts and how paths relate to requirements | Exact location or graph adjacency does not grant mutation permission |
| Source evidence | Establishes current behavior, interfaces, and dependencies | Must expose relevant semantics, not merely a package header |
| Verification methods and evidence | Defines how a claim will be checked and what is already known | A method, a successful result, and acceptance remain distinct |
| Relevant prior evidence or rejection | Supplies a specific constraint or novel observation | No unrelated recall, raw ledger replay, or repeated diagnostics |
| Output contract | Bounds the result Orchard can validate | One consistent stage-specific schema and set of limits |
| Provenance references | Makes source and authority claims verifiable | Full evidence remains durable; the prompt uses canonical references |

### 1. Typed Relevance and Authority

Each selected item must have a deterministic inclusion reason linked to an active requirement, coordinate, uncertainty, invariant, or verification obligation. Foundation weighting, lexical overlap, directory proximity, and graph adjacency may discover candidates but cannot establish final relevance or authority.

Context must distinguish implementation owners, contracts, dependency producers/consumers, regression evidence, verification methods, operational guards, documentation, and recalled observations. Its disposition must distinguish candidate mutation analysis, admitted actionable operations, evidence-only material, optional support, and deferred work.

Repository analysis may nominate a change; that nomination is not an admitted coding operation. A file included to explain review or promotion machinery is not thereby a candidate for source mutation. Evidence-only material is not necessarily irrelevant: it has a different role and should receive an appropriate representation and budget.

The compiler preserves forward and reverse traceability as defined by the Attention ADRs. No orphan requirement may disappear through compaction; no orphan actionable path may enter through retrieval. Complete admitted scope has no arbitrary path-count cap. An invocation may have a narrower active slice only when the remaining obligations and coverage are explicit.

### 2. Semantic Adequacy Before Fit

Evidence must be sufficient for the decision requested of the model. Applicable source excerpts should expose relevant declarations, signatures, behavior, data contracts, dependency relationships, and test assertions. The compiler must retain revision, full-source hash, excerpt location, and enough surrounding structure to interpret the excerpt correctly.

Package/import preambles, lexical match counts, filenames, or a valid full-file hash alone cannot prove implementation behavior. Lexical summaries may aid discovery but must not masquerade as source evidence. A prefix fallback may locate a file without making it adequate for analysis.

Compaction must revalidate adequacy. If an essential producer, consumer, invariant, or test relationship loses its supporting evidence, the compiler reports inadequate evidence instead of accepting a smaller but misleading prompt. Full-file hashes identify the source artifact; they do not claim that an excerpt represents all of its semantics.

### 3. Consistent Stage Contract

The system instructions, envelope, output schema, parser, deterministic compiler, and validator must agree on the stage's responsibility and limits. Prefer one versioned contract from which these surfaces are generated or checked.

An analysis-candidate stage must not advertise execution-plan fields it prohibits the model from emitting. Limits must identify whether they govern the active invocation, candidate paths, plan operations, or total admitted scope. Legacy limits cannot remain as contradictory prompt prose.

### 4. Canonical Evidence Without Repetition

Serialize each source identity and authority item once in a canonical evidence table, with stable invocation-local references for scope mappings, excerpts, citations, and verification relationships. Orchard resolves those references back to durable coordinate IDs, manifest identities, source hashes, and accepted authority.

Deduplicating representation does not remove provenance or weaken validation. The compiler must reject missing, ambiguous, or inconsistent references. A model's short citation is meaningful only through the pinned mapping that Orchard validates.

Avoid repeated full paths, hashes, requirements, schemas, and retry text across independent inventories. Complete-scope validation inventory remains available to deterministic policy; it need not become repeated model-facing prose. Selected optional support must not silently become mandatory evidence merely because it was collected.

### 5. Honest Budget Accounting

Input tokens, output tokens, serialized bytes, source bytes, and provider overhead are separate quantities with named units. Select the binding before final admission and count the fully serialized provider request using its matching tokenizer and chat format when available. Pin tokenizer identity/version, binding identity, and counting method in provenance.

Budgeting includes system instructions, authority, source evidence, schemas, tool definitions where applicable, chat formatting, output reserve, and an explicitly justified safety margin. Any stage allocation, including the current 70% rule, must have a named policy, rationale, and version; it must not be mistaken for physical model capacity.

When compatible tokenization is unavailable, a conservative fallback remains allowed. It must expose its method, units, assumptions, uncertainty, and overhead allowance. A byte upper bound is not a measured token count. A rejection caused only by that fallback must say that capacity could not be established under the fallback policy, not that tokenizer-measured exhaustion occurred. An arbitrary bytes-per-token ratio is not an acceptable substitute for a binding-aware count.

User aperture overrides remain explicit authority under ADR 029. Orchard must not raise an override, silently alter a reserve, discard mandatory evidence, or grant unchanged retry authority to make an invocation appear to fit.

### 6. Quality and Capacity Diagnostics

Before inference, produce a versioned context-quality report linked to the run, authority revisions, selected coordinates, manifest, binding, and compilation policy. Record:

- per-component bytes and token counts, counting method, and effective limits;
- selected, required, optional, omitted, and deferred evidence counts;
- each item's role, inclusion reason, authority origin, and supported decision;
- excerpt locations, source hashes, relevant semantic coverage, and adequacy failures;
- duplicated identities or text and their representation cost;
- unresolved dependencies, instruction conflicts, and stale or missing evidence;
- active/deferred scope coverage and what the invocation cannot claim complete; and
- compaction attempts, their semantic effects, and the exact rejection predicate.

Diagnostics must distinguish missing authority, unavailable source, stale provenance, inadequate evidence, conflicting instructions, tokenizer uncertainty, actual token-budget overflow, provider/resource unavailability, and lost coverage. A helper's generic null result cannot be reported as token overflow for every failure mode.

Persist a failed-compilation report even when no full prompt is produced. Reports must not require another model invocation to explain a deterministic rejection, and should reference sensitive source rather than unnecessarily duplicate it. Historical attempts remain immutable; reconstructed audits are labeled as reconstructions.

### 7. Recovery Order

Recover in this order:

1. Diagnose the exact failure with a component and relevance report.
2. Correct counting, contract conflicts, duplicated representation, or defective retrieval.
3. Compile semantically adequate evidence using the unchanged admitted aperture.
4. If genuinely useful mandatory context still cannot fit, propose an explicit dependency-aware analysis slice or a separately authorized aperture change.

Slicing is a remedy for measured, relevant workload size, not a way to conceal accounting or retrieval defects. A slice preserves complete objective authority, active and deferred requirements, prerequisites, shared contracts, and cumulative coverage. Aggregation must establish full coverage and consistency before claiming objective completion.

## Consequences

- Context quality becomes an inspectable Orchard responsibility rather than prompt-engineering intuition.
- Valid coordinates and valid source hashes remain necessary but are no longer confused with semantic sufficiency.
- Smaller prompts do not automatically imply better reasoning; useful evidence must survive compaction.
- Larger model windows do not excuse irrelevant retrieval or inconsistent instructions.
- Canonical references reduce overhead while preserving full audit evidence outside the model window.
- Tokenizer and chat-format compatibility add implementation and versioning obligations.
- Legacy conservative budgeting remains available, but its uncertainty is observable and cannot be mislabeled as measured exhaustion.
- A blocked context can be repaired without rewriting historical authority, spending inference, or weakening acceptance.

## Alternatives Considered

### Increase the Aperture First

Rejected as the default response. It can hide accounting defects, cost more, and leave relevance and contradictory instructions unchanged. An explicit increase remains legitimate when measured adequate context actually needs it.

### Cap the Number of Paths

Rejected as a quality rule. File count does not measure tokens, relevance, adequacy, or transversal scope coverage. Discovery limits must be reported separately and cannot silently narrow authority.

### Keep Only Smaller Excerpts

Rejected as a general solution. Headers and tiny fragments can fit while proving nothing about behavior. Compaction must preserve the evidence needed for the active decision.

### Let a Model Summarize or Score Its Own Context

Rejected as admission authority. A model may assist bounded investigation, but cannot grant relevance, invent missing relationships, or certify provenance and completeness. Deterministic quality reports precede inference.

## Open Questions and Completion Gate

This proposal is incomplete pending an exact implementation trace. The audit establishes context defects but does not establish that Attention frames themselves are inadequate. Before accepting this decision or implementing a new abstraction, trace the active path from admitted authority through frame compilation, evidence selection, excerpt generation, compaction, envelope serialization, system instructions, provider formatting, and validation.

The trace must answer:

- Which model-backed stages actually compile and consume an Attention frame, and which construct independent context?
- Where are frame relationships, evidence-only dispositions, active/deferred coverage, and permissions retained, weakened, or omitted?
- Can later retrieval, envelope construction, or compaction add irrelevant material or remove necessary semantics despite a structurally valid frame?
- Which contract controls the exact request sent to the provider, and does it agree with the stage's parser and validator?
- What minimal changes and executable checks make the final request a faithful implementation of the frame rather than a parallel description of the same work?

Faithfulness requires an auditable mapping from every model-facing actionable item to frame authority, every active obligation to adequate evidence or an explicit unresolved disposition, and every transformed reference to pinned provenance. Structural frame validity alone does not establish these properties. Findings must distinguish observed code paths from proposed remedies and avoid attributing analysis failures to a coding-stage frame that may never have governed that invocation.

The current scope is context quality and faithful frame compilation. Model-resource scheduling, semaphores, resource leases, provider KV-cache swapping, and a general offline semantic adjudicator are not prerequisites for completing this decision.

## Implementation and Validation

First instrument compilation and distinguish failure causes. Then implement binding-aware accounting, aligned output contracts, canonical evidence references, typed relevance, and adequacy-preserving excerpts. Reassess measured workloads before adding analysis slicing. These are separate changes with their own regression evidence; this ADR does not declare them delivered.

Validation must cover:

- different byte/token ratios, multilingual text, tokenizer mismatch, and provider formatting overhead;
- explicit reserve policy and unchanged user overrides;
- a fixed metadata floor that exceeds the byte fallback but fits the matching tokenizer's token budget;
- missing required paths reported as evidence failure rather than overflow;
- headers-only excerpts rejected for behavior analysis even when they fit;
- relevant declaration, producer/consumer, and regression evidence surviving compaction;
- verification and operational material retained with evidence-only roles and no mutation authority;
- consistent system instructions, schema, parser, and operation limits;
- canonical references resolving to identical pinned authority without repeated inventories;
- complete large coordinate inventories, explicit deferred coverage, and no silent truncation;
- independent reporting of validity, relevance, adequacy, capacity, and downstream outcomes; and
- replay-compatible historical records and reproducible failed-compilation reports without inference.