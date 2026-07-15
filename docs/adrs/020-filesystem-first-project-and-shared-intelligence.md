# ADR 020: Filesystem-First Project and Shared Intelligence

## Status
Proposed

If accepted, this ADR supersedes ADRs 003, 005, and 013. It partially supersedes the storage and retrieval decisions in ADRs 006, 012, and 014.

## Context
Orchard must preserve project state, source material, decisions, evidence, execution history, and learned engineering knowledge. Both humans and agents need to inspect, repair, version, search, and reason about that information without requiring a database console, proprietary binary format, or hidden in-process state.

Not all retained information has the same authority. Raw events are not approved knowledge. Embeddings are not intelligence. A model-generated rule is not project policy. Project-specific findings must not silently become universal preferences. Orchard therefore needs explicit storage domains and a governed intelligence lifecycle.

The `epistemic-filter` project provides useful research concepts: independent relevance and derivation gates, immutable provenance, contradiction records, accepted and rejected candidates, reflection windows, and asymmetric knowledge inheritance. Its current Python implementation targets training-corpus construction and is not a production Orchard storage or execution dependency.

## Decision
Human-readable filesystem records are Orchard's authoritative state. Databases, vector stores, embeddings, caches, and in-memory views are derived artifacts that can be deleted and rebuilt without losing authority.

Orchard separates the following domains:

- **Project state**: current projects, work items, relationships, assignments, and approved configuration.
- **Journal**: append-only commands, events, transitions, and execution records.
- **Documents**: imported or authored source material.
- **Evidence**: test results, benchmark reports, diffs, reviews, logs, and other verifiable outcomes.
- **Intelligence**: distilled observations, inferences, practices, procedures, preferences, contradictions, and workflows.
- **Indexes**: rebuildable lexical, semantic, and structural retrieval projections.
- **Ephemeral state**: temporary plans, partial model output, locks, and active-process metadata.

Intelligence exists at two primary scopes:

1. **Project intelligence** contains knowledge whose validity depends on a repository, module, project, environment, or local decision.
2. **Shared intelligence** contains explicitly promoted knowledge intended to apply across projects.

Project intelligence takes precedence within its declared scope. Shared intelligence may be qualified or overridden locally without mutating the shared source record. Project findings never flow into shared intelligence automatically.

Every intelligence record must preserve enough information to audit its origin and lifecycle:

- stable ID and kind;
- shared, project, repository, module, or narrower scope;
- candidate, approved, rejected, superseded, or revoked status;
- statement and structured payload where applicable;
- exact source paths, excerpts, revisions, and hashes;
- records from which it was derived;
- derivation or rationale;
- supporting and contradicting records;
- extractor, prompt, and schema versions;
- creation, review, and supersession metadata.

Observations and inferences are separate records. Repository or document extraction creates evidence-backed observations first. Derived principles, practices, preferences, and workflows remain candidates until deterministic verification or explicit review approves them.

Contradictions are represented as relationships rather than destructive overwrites. A contradiction records its scope and classification, such as scoped exception, near miss, misattribution, framing trap, or false claim. Contradictions create review work; they do not automatically reject either side.

Durable mutations use complete-file writes to a temporary path, flush where required, and atomic replacement. Append-only journals use stable event IDs and recoverable framing. Aggregate size and measured access patterns determine whether an authoritative concept uses one manifest, one aggregate file, or a directory of records; Orchard will not create thousands of tiny files without a demonstrated navigation or concurrency benefit.

`epistemic-filter` may act as an offline or replaceable producer of candidate records. Orchard owns record schemas, authority, review, promotion, retrieval, and workflow execution. Model-weight training is an optional downstream compilation target; it never replaces authoritative filesystem intelligence.

## Consequences
- Humans and agents can inspect Orchard's durable state with ordinary filesystem tools.
- Backups and synchronization can operate on directories and files.
- Intelligence remains reversible and auditable because observations, derivations, reviews, and supersession are retained.
- Orchard requires schema validation, atomic-write utilities, recovery tests, and clear ownership for filesystem mutations.
- High write concurrency and complex global queries may eventually justify a database projection, but that projection cannot become authority without a new ADR.
- Semantic retrieval quality depends on rebuildable indexes and does not determine whether a claim is approved truth.
- Candidate and rejected intelligence may remain searchable for review but must not enter normal agent context as established practice.
- This ADR does not define which storage or intelligence feature is implemented first.