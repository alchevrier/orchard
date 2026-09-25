# ADR 055: Coordinate-Aware Work-Definition Admission

## Status

Proposed

This decision extends ADR 024, ADR 025, ADR 027, ADR 028, ADR 044, ADR 046, ADR 052, ADR 053, and ADR 054. It introduces revision-pinned repository coordinates as explicit work-definition authority. It does not change historical work definitions, workflow runs, or repository intelligence manifests.

## Context

ADR 054 makes repository intelligence durable and compiles model context from a bounded graph-local aperture. A model-backed repository-analysis run therefore needs exact coordinates into the manifest: a path, and later a declaration or other stable node identity, at the run's pinned repository revision.

The self-hosting Inbox delivery run demonstrated a remaining authority gap. Its admitted work definition named source files in prose and supplied broad selector globs for consumer and backend inventories. The manifest could prove that no exact accepted scope anchor existed, so repository analysis correctly refused to dispatch a model. The pilot could explain the refusal, but it still advertised a retry because the work-definition admission layer had accepted selector inventory without durable graph coordinates.

A wildcard selector is useful for complete-scope validation. It is not sufficient authority to choose a bounded model aperture. Treating it as context authority would reintroduce the selector-wide repository expansion that ADR 054 rejects. Conversely, requiring a human or model to enumerate every relevant adjacent file defeats graph-local navigation.

Orchard needs an admission rule that accepts a small set of exact, revision-pinned coordinates; proves that they resolve against the required manifest; persists the resolved identity; and lets the manifest derive the bounded neighborhood. Missing, ambiguous, stale, or unsupported coordinates must become an explicit work-definition repair, not a late model-analysis failure.

## Decision

A work definition that can start model-backed repository analysis will declare one or more **repository coordinates**. A coordinate is a revision-pinned reference to a manifest node that identifies where admitted work begins.

The principle is:

> A work definition authorizes bounded repository reasoning by naming verified coordinates; the repository intelligence manifest determines the relevant neighborhood.

### Coordinate Forms

The initial supported coordinate is an exact repository-relative tracked-file path. It resolves to a file node in a compatible repository intelligence manifest for the definition's required repository revision.

Future coordinate forms may include stable declaration, route, persistence-record, test, module, or configuration node identifiers. A future form must define deterministic resolution, source evidence, revision binding, compatibility behavior, and validation before it can be admitted.

A coordinate record contains at least:

- a stable coordinate ID within the definition revision;
- coordinate kind;
- submitted path or node reference;
- repository ID and required revision;
- resolved manifest key and node ID;
- resolved source hash;
- owning scope clause or requirement reference; and
- resolution status and deterministic diagnostic where unresolved.

The accepted definition persists both the submitted reference and its resolved form. A later manifest, repository, extractor, policy, or authority revision never rewrites the historical coordinate.

### Admission Rules

Before a definition is `READY` for a workflow that can invoke repository analysis, Orchard must:

1. resolve the definition's repository binding and pinned revision;
2. ensure or reuse a compatible `READY` repository intelligence manifest;
3. resolve every required coordinate against that manifest;
4. verify that each path is tracked and exact, each node is supported, and no coordinate resolves ambiguously;
5. persist the resulting coordinate evidence in the admitted definition; and
6. record the manifest key, resolved node IDs, source hashes, and validation result.

The definition is not `READY` for model-backed repository analysis when no coordinate resolves. It enters `NEEDS_CLARIFICATION` or `NEEDS_INVESTIGATION` with a typed diagnostic that distinguishes missing paths, basename-only references, wildcard-only selectors, stale revisions, unsupported node kinds, and unresolved manifest boundaries.

A path embedded in prose is not a coordinate merely because a parser can recognize it. The author must submit it through the typed coordinate field or an equivalent accepted exact-path scope form. Orchard may offer deterministic candidate coordinates derived from prose, selectors, or repository search, but these remain proposals until explicit human admission.

### Selectors and Coordinates Have Separate Roles

Repository evidence selectors remain part of a work definition. They establish complete-scope inventory, coverage requirements, path-affine test relationships, and later plan validation.

Selectors do not create model-context authority. In particular:

- wildcard matches are retained for validation but do not automatically enter a prompt;
- a selector may be linked to a coordinate but cannot replace it;
- selector expansion may identify candidate coordinates for review, but must not silently admit them; and
- graph-local traversal begins only from accepted resolved coordinates.

This preserves both complete validation coverage and bounded inference.

### Workflow and Successor Semantics

A workflow run embeds the accepted work-definition revision and coordinate evidence in its immutable context manifest. Repository analysis uses those coordinates and the run's pinned revision to query the graph-local aperture.

Historical definitions and runs remain valid historical records. They are not rewritten to add coordinates. When an existing run lacks required coordinates, Orchard must report a deterministic coordinate-admission blocker and offer a human-authorized successor-definition path. The successor receives a new work-definition revision and, once admitted through normal planning and workflow gates, a new run.

A retry is not authorized when the latest deterministic outcome says the required coordinates are absent, stale, ambiguous, or unresolved. Pilot status must instead project a lower-cost action that explains the required definition repair. It must not advertise a model-backed retry until a compatible successor definition supplies valid coordinates.

### Pilot and Trace Projections

Pilot status and repository-intelligence traces expose compact coordinate evidence without embedding arbitrary source:

- definition revision and required repository revision;
- manifest key and coordinate IDs;
- resolved and unresolved coordinate counts;
- exact submitted references, resolved node IDs, and source hashes where authorized;
- missing or ambiguous coordinate diagnostics;
- graph-query roots selected from coordinates; and
- the authorized non-model repair action when coordinate admission is incomplete.

The trace distinguishes an authority problem from a graph-extraction defect. A coordinate that exists in source but cannot resolve to a manifest node identifies an intelligence or extractor limitation. A coordinate absent from the definition is a definition-authority gap. Neither condition permits selector-wide fallback context.

## Consequences

Repository analysis becomes deterministic earlier in the delivery lifecycle. Orchard can reject insufficiently located work before reserving model resources or building a prompt.

Work-definition authors supply a small set of durable coordinates rather than a complete change list. The manifest remains responsible for discovering nearby consumers, contracts, boundaries, tests, and unresolved relationships.

Definitions gain a dependency on compatible repository intelligence at admission time. Deterministic manifest extraction may therefore occur before a model-backed workflow run begins. This cost is local, cached by manifest identity, and preferable to repeatedly spending inference budget on structural rediscovery.

Existing definitions that rely only on prose or broad selectors require a successor definition before they can use graph-local model analysis. This is intentional: Orchard must not silently reinterpret their historical authority.

## Alternatives Considered

### Treat Every Selector Match as a Coordinate

Rejected. A wildcard selector can match a large or unrelated surface and would turn validation inventory back into model context authority.

### Infer Coordinates from Prose at Dispatch Time

Rejected. A model or heuristic may nominate useful paths, but silent admission changes the meaning of a human-accepted definition and cannot distinguish intention from incidental text.

### Require Every Relevant File in the Definition

Rejected. It duplicates the repository map in every work definition, makes definitions brittle, and removes the graph's purpose: bounded structural expansion from a small starting point.

### Allow Graph Queries Without Coordinates

Rejected. A graph cannot determine which connected neighborhood represents the admitted task without roots. Falling back to global ranking or selector-wide context recreates the original context-budget failure.

## Implementation Notes

The implementation should:

1. add versioned coordinate and resolution-evidence fields to work-definition submissions and manifests;
2. provide deterministic exact-path coordinate validation against a compatible manifest;
3. preserve existing selectors as separate validation inventory;
4. compile a coordinate-admission assessment with typed repair diagnostics;
5. require successful coordinate admission before starting model-backed repository analysis;
6. embed accepted coordinate evidence in workflow context manifests and analysis provenance;
7. update pilot action compilation to prefer coordinate repair over model retry when coordinates are insufficient;
8. provide a governed successor-definition flow for immutable historical definitions and active or blocked runs; and
9. add later symbol-coordinate forms only through explicit compatible schema and extractor support.

## Validation

- A definition with exact tracked paths resolves to stable manifest node IDs and source hashes at its required revision.
- A basename-only prose reference, wildcard-only selector, missing path, ambiguous reference, stale revision, or unsupported node kind cannot become `READY` for model-backed analysis.
- A compatible manifest reused at admission preserves the same coordinate resolution; a changed commit, extractor version, policy version, or authority projection requires revalidation.
- A selector may validate complete inventory but cannot add prompt files unless graph traversal selects them from an accepted coordinate.
- A historical definition and run remain byte-for-byte and checksum-compatible after a successor definition adds coordinates.
- A blocked run with absent coordinates projects a deterministic definition-repair action and no model-backed retry.
- A resolved coordinate whose expected graph node or edge is missing produces an extractor/intelligence diagnostic, not a broad-context fallback.
- Graph-query and context-compilation traces link selected roots to accepted coordinate IDs, manifest key, source hashes, and definition revision.
