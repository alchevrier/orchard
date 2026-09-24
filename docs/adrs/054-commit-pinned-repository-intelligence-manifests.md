# ADR 054: Commit-Pinned Repository Intelligence Manifests

## Status

Accepted

This decision defines the authority and pilot contract for repository intelligence. Extraction, persistence, graph-query, bounded-context compilation, and endpoint implementation follow incrementally.

Extends ADR 005, ADR 012, ADR 016, ADR 023, ADR 028, ADR 036, ADR 046, ADR 048, ADR 049, ADR 052, and ADR 053. It preserves their durable evidence, repository binding, bounded model aperture, coding authority, Attention, and pilot-operation boundaries.

## Context

Repository analysis currently compiles model context from path selectors, lexical ranking, and focused source excerpts. This makes file selection observable, but it does not provide a durable representation of repository structure.

The self-hosting Inbox milestone exposed the consequence. Broad selectors for frontend consumers and backend execution gates expanded into many files. The analysis aperture treated selector-matched files as required model context, even when the files had no direct structural relationship to the requested frontend contract, transport boundary, model, authority, or test. The model therefore had to reconstruct ownership and impact relationships from source text on every attempt.

An LLM is unsuitable as the authority that determines these relationships. It may miss consumers, invent edges, vary across attempts, and consume the same context budget that Orchard needs for bounded change reasoning. A pilot must be able to ask Orchard whether intelligence for a run's reserved commit already exists, is compatible, is being built, or must be rebuilt. It must not infer cache freshness from logs or rebuild repository structure through repeated model analysis.

Orchard needs a machine-readable, immutable repository intelligence manifest for each repository commit and extractor policy version. The manifest must contain enough deterministic structure to identify transport, models, architectural roles, and role-to-role impact paths without requiring a model to rediscover them.

## Decision

Orchard will maintain immutable, machine-readable **Repository Intelligence Manifests** pinned to a repository commit.

The manifest identity is:

$$
\text{manifestKey} =
(\text{repositoryId},\ \text{commitHash},\ \text{extractorVersion},\ \text{policyVersion})
$$

The governing principle is:

> **Orchard deterministically compiles repository structure once per compatible commit; models reason only over a bounded graph-local aperture.**

### Manifest Authority

A manifest is derived intelligence, not a source-of-truth mutation of the repository or workflow. It is durable evidence for a specific commit and extraction policy.

Each manifest is immutable. Orchard must never modify a manifest to represent a later commit. A changed commit receives a distinct manifest, either by complete deterministic extraction or by incrementally deriving a new manifest from a compatible parent and a verified repository diff.

The manifest state is one of:

- `ABSENT`: no compatible manifest exists;
- `BUILDING`: one deterministic builder owns construction for the exact key;
- `READY`: a complete compatible manifest exists and passed validation;
- `FAILED`: deterministic extraction failed, with durable diagnostic and source evidence; or
- `STALE`: a manifest exists for the repository but not for the run's required commit, extractor version, or policy version.

Only `READY` manifests may drive bounded repository-analysis context or impact authority.

### Machine-Readable Graph

The primary artifact is a typed manifest, not a human narrative. It contains stable node identifiers, typed edges, source evidence, hashes, and materialized indexes.

Nodes include at least:

- repository file;
- module and source root;
- declaration, type, function, route, and test;
- request/response contract and persisted record where deterministic extraction can identify them; and
- toolchain verification target where available.

Every node records its source path, source-content hash, extractor identity, and source span or equivalent extractor evidence.

Every authoritative role classification and edge records its deterministic provenance: extractor rule ID, source-evidence references, extraction version, and any declared confidence or limitation. Orchard must be able to explain why it classified a node as `TRANSPORT_SERVER`, `AUTHORITY`, `MODEL`, or another role, and why it asserted a relationship, without asking a model to reinterpret the source.

Edges include at least:

- `DECLARES`;
- `IMPORTS`;
- `REFERENCES`;
- `CALLS`;
- `ACCEPTS` and `RETURNS` for transport contracts;
- `INVOKES` for transport-to-authority calls;
- `MUTATES` and `PERSISTS` for authority-to-model and persistence relationships;
- `DERIVES` for projections; and
- `VERIFIES` for tests and verification targets.

The manifest materializes both forward and reverse indexes by path, declaration, role, and edge endpoint. A pilot or deterministic context compiler must not scan repository source or ask a model to reconstruct inverse consumers when an index can answer the query.

### Deterministic Role Classification

Orchard's deterministic extractor classifies repository nodes into architectural roles when source evidence supports the classification. Initial roles include:

- `TRANSPORT_CLIENT`;
- `TRANSPORT_SERVER`;
- `MODEL`;
- `AUTHORITY`;
- `SERVICE`;
- `PERSISTENCE`;
- `PROJECTION`;
- `UI`;
- `TEST`; and
- `TOOLCHAIN`.

Extraction uses parsers, compiler or language-server facts where available, source-root and module conventions, annotations, route declarations, imports, resolved references, serialization declarations, Compose declarations, and test declarations. A role is authoritative only when deterministic evidence supports it.

Nodes without sufficient evidence remain `UNCLASSIFIED`. This is preferable to a plausible but unsupported role assignment.

An LLM may later produce candidate role or edge annotations for retrieval assistance, but those annotations are non-authoritative. They may not create hard context obligations, assert complete impact coverage, authorize source mutation, or replace deterministic evidence.

### Semantic Completeness and Temporal Boundaries

The manifest is Orchard's machine-actionable representation of repository meaning. It is not merely a file-retrieval index.

Complete semantic equivalence for arbitrary software is not a valid requirement: reflection, configuration, external services, runtime data, scheduling, and time can determine behavior outside what static extraction can prove. Orchard instead requires **evidence-scoped completeness**:

> **For supported language constructs and declared boundaries, the manifest is complete, replayable, and verifiable. Unsupported or dynamic behavior is represented as an unresolved boundary, never as guessed structure.**

The manifest therefore represents both static structure and temporal boundary semantics. Static edges explain potential references and dependencies. Temporal edges explain externally meaningful state and causality when deterministic evidence identifies them, including:

- `RECEIVES_EVENT`;
- `EMITS_EVENT`;
- `ADMITS_COMMAND` and `REJECTS_COMMAND`;
- `APPENDS_RECORD`;
- `OBSERVES_STATE`;
- `SCHEDULES_WORK`;
- `ACQUIRES_RESOURCE` and `RELEASES_RESOURCE`; and
- `DERIVES_PROJECTION`.

Modules may allocate private transient state without declaring it to Orchard. Once that state crosses a transport, command, persistence, projection, scheduling, resource, or event boundary, the extractor records the typed boundary relationship and its source evidence where supported.

An unresolved boundary records its kind, source evidence, and required handling policy. Examples include reflection, configuration-selected implementations, unresolved dynamic dispatch, native calls, and external services. A graph query that crosses an unresolved boundary must report the uncertainty and require explicit context or policy; it must not silently claim complete impact coverage.

Repository analysis uses the joined static and temporal graph to answer impact questions. A change to an authority transition can therefore compile context for the affected transport contracts, durable records, projections, scheduled work, UI observers, and verification flows, rather than only files that import the changed symbol.

### Repository Intelligence Tracing Module

Orchard will provide a repository intelligence tracing module for development diagnostics and governed audit. The module records the causal execution path by which Orchard ensured a manifest, queried its graph, compiled a bounded context, invoked a model-backed operation, and validated the resulting plan.

Tracing complements durable authority records; it does not replace them. A trace is diagnostic evidence of how an operation used authority. Workflow events, manifests, attempts, plans, model-execution observations, and verification records remain the authoritative records for their respective decisions.

Each trace has a stable trace ID and typed spans. Spans carry correlation references where applicable:

- repository ID, commit hash, manifest key, extractor version, and policy version;
- workflow run, work item, attempt, execution plan, and work-package IDs;
- operation mode and initiator;
- graph query identity, selected-node count, omitted-node count, and unresolved-boundary count;
- model profile, binding fingerprint, prompt hash, token estimates, and provider phase references; and
- deterministic status, diagnostic code, elapsed duration, and durable evidence references.

Initial span kinds include:

- `MANIFEST_ENSURE`;
- `MANIFEST_EXTRACT`;
- `MANIFEST_VALIDATE`;
- `GRAPH_QUERY`;
- `CONTEXT_COMPILE`;
- `REPOSITORY_ANALYSIS`;
- `MODEL_PROVIDER_EXECUTION`;
- `PLAN_VALIDATE`;
- `CODING_EXECUTION`; and
- `VERIFICATION_EXECUTION`.

The tracing module records structure and hashes by default, not raw source, secrets, prompts, model responses, credentials, or unrestricted command output. A trace links to separately governed durable artifacts when an authorized developer or auditor needs deeper evidence.

Traces must make deterministic decisions inspectable. For example, a context-budget block must identify the manifest key, graph query, selected and omitted node counts, required source paths or node IDs, estimated prompt tokens, admitted budget, and the exact deterministic rejection. A developer or auditor must be able to answer why an operation selected a path, omitted a path, reused a manifest, rebuilt a manifest, or refused model admission without reproducing the operation or inspecting transient logs.

Tracing must also support **classification and relationship diagnosis**. For every node or edge used by a graph query, an auditor can retrieve a compact explanation containing:

- the node or edge ID and manifest key;
- assigned roles or relationship type;
- extractor rule IDs and source-evidence references that produced the assignment;
- competing candidates that were rejected or downgraded, where the extractor evaluates alternatives;
- unresolved boundaries that limit confidence or traversal; and
- the graph-query step that selected, omitted, or stopped traversal at the node or edge.

This allows a pilot to distinguish a repository defect from an intelligence defect. If an expected consumer is absent, the pilot can determine whether the extractor missed an import or boundary, the graph policy excluded a valid edge, the context compiler applied an incorrect traversal rule, or the repository deliberately contains no supported static relationship. The pilot must not compensate by silently widening context or invoking a model to guess the missing relationship.

The module exposes compact read-only trace projections for development and audit. Pilot status links only the current relevant trace or summary; it must not embed an unbounded trace history into the operator context window.

### Pilot Ensure Operation

Orchard exposes one deterministic operation for a workflow run:

```text
POST /api/repository-intelligence/runs/{runId}/ensure
```

The operation resolves the run's repository reservation and pinned commit, computes the required manifest key, and then:

1. returns `READY` with disposition `REUSED` when an exact compatible manifest exists;
2. returns `BUILDING` when another operation owns construction of the same key;
3. deterministically builds and persists the manifest, then returns `READY` with disposition `BUILT`, when it is absent or stale; or
4. returns `FAILED` or `BLOCKED` with a durable diagnostic when the commit, source, extraction policy, or persistence cannot be resolved.

The operation is deterministic work, not a model-backed action. It is available in both `AUTONOMOUS` and `PILOTED` operation modes, while respecting repository binding, revision, concurrency, and persistence safety.

Pilot status advertises `ensure-repository-intelligence` whenever a repository-analysis run lacks a compatible manifest. Repository analysis must not begin a model-backed attempt without a compatible `READY` manifest reference.

### Graph-Local Analysis Context

Repository analysis compiles its working context from graph relationships rather than treating every wildcard selector match as required prompt content.

The compiler must:

1. retain every exact accepted path as deterministic authority evidence;
2. resolve accepted selectors to manifest nodes and complete inventory evidence;
3. choose graph-near owners, transport boundaries, model contracts, direct consumers, and linked tests as the bounded model aperture;
4. preserve the complete selector inventory outside the prompt for deterministic validation; and
5. record the manifest key, graph query, selected nodes, omitted nodes, and source hashes in attempt provenance.

The model sees a compact graph-local source aperture. Orchard retains the full manifest and validates that plans respect the complete accepted scope. Authority inventory and model working context are therefore intentionally distinct.

## Consequences

Repository context becomes reproducible for a commit and extraction policy. Repeated analysis attempts reuse durable structural intelligence instead of repeatedly asking a model to rediscover imports, consumers, transport boundaries, and tests.

Pilot operation becomes simpler and cheaper:

```text
pilot status
  -> ensure repository intelligence
  -> reused or built commit-pinned manifest
  -> graph-local repository analysis
```

The initial extractor will have incomplete language coverage. `UNCLASSIFIED` nodes and absent edges remain explicit limitations rather than silently guessed structure. Extraction failures become deterministic blockers, not reasons to fall back to broad ungrounded model context.

Manifest storage grows with commits. Orchard may later compact or derive manifests incrementally, but compaction must preserve immutable commit identity and evidence binding.

## Implementation Notes

The initial implementation should:

1. define versioned manifest, node, edge, role, index, and extraction-evidence schemas;
2. persist checksummed immutable manifests beneath the Orchard project workspace store;
3. implement a single-flight `ensure` service keyed by repository, commit, extractor version, and policy version;
4. extract Kotlin/Gradle source roots, files, declarations, imports, tests, Ktor transport declarations, Compose UI declarations, and serialization contracts deterministically;
5. extract supported command, persistence, projection, event, scheduling, and resource boundary relationships;
6. record unsupported or dynamic boundaries explicitly with source evidence and handling policy;
7. materialize forward and reverse indexes;
8. expose the run-scoped `ensure` endpoint and compact pilot projection;
9. require a `READY` manifest before repository-analysis model admission; and
10. implement typed repository-intelligence traces and compact read-only development/audit projections; and
11. replace selector-wide required prompt paths with a graph-local context compiler while retaining the full inventory for validation.

## Validation

- Ensuring intelligence twice for the same repository, commit, extractor version, and policy version returns `BUILT` then `REUSED` with the same manifest key.
- A changed repository commit does not reuse the previous commit's manifest.
- A changed extractor or policy version does not reuse an incompatible manifest.
- Concurrent ensures for the same key produce one builder and one durable manifest.
- Manifest replay rejects invalid checksums, duplicate node IDs, dangling edges, invalid role values, and source-hash mismatches.
- Deterministic extraction identifies a known Ktor route, transport DTO, authority, Compose UI consumer, and test relationship in a focused fixture.
- A role or edge audit projection identifies its extractor rule, source evidence, manifest version, and unresolved-boundary limitation without re-reading repository source or invoking a model.
- Deterministic extraction identifies supported command-to-authority, authority-to-record, record-to-projection, and event boundary relationships in a focused fixture.
- A graph query crossing reflection, configuration-selected implementation, unresolved dynamic dispatch, native code, or an external service returns the unresolved boundary and does not claim complete impact coverage.
- A manifest ensure, graph query, context compilation, model admission decision, and plan validation produce correlated typed spans without recording raw source, prompts, responses, secrets, or unrestricted command output.
- A context-budget rejection trace identifies its manifest, graph query, selected and omitted context, token estimate, admitted budget, and deterministic diagnostic.
- A graph-query trace identifies why each selected relationship was traversed and why an expected but unavailable relationship was excluded, unresolved, or absent.
- Repository analysis refuses model admission without a compatible `READY` manifest.
- A broad selector inventory remains complete in durable evidence while its model aperture contains only graph-local owners, consumers, boundaries, and tests.
- Pilot status advertises ensure when intelligence is absent or stale and does not advertise a repository-analysis model action until the manifest is ready.