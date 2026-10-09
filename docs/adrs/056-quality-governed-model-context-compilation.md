# ADR 056: Quality-Governed Model Context Compilation

## Status

Proposed

Implementation completion verified on 2026-10-09: `:backend:adrCompletionGate` passed all 14 integrated acceptance criteria with no failed or skipped backend tests. The decision remains Proposed pending explicit adoption; gate success does not itself grant architecture authority.

Clarifies completion of the Attention integration described by [ADR 048](048-deterministic-attention-compilation.md) and [ADR 049](049-attention-layer-definition.md), refines the budgeting boundary in [ADR 028](028-context-bounded-model-profiles.md), and extends [ADR 029](029-user-configurable-model-apertures.md), [ADR 051](051-resource-bounded-persistence-and-stop-authority.md), [ADR 054](054-commit-pinned-repository-intelligence-manifests.md), and [ADR 055](055-coordinate-aware-work-definition-admission.md). It does not supersede their authority, provenance, resource, or historical-record invariants or introduce an Attention replacement. Integration repairs and proposed additional quality guarantees are distinguished below. The dated implementation sections record delivered behavior and its limits; broader quality guarantees remain proposed.

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

### Integration Repair Versus Additional Guarantees

The implementation trace below establishes that repository analysis constructs its context independently of Attention. Relative to the governing Attention design, this is a missing integration, not evidence that Orchard needs a new context architecture. Coding integrates frames later, but source retrieval and final request assembly do not establish that the materialized context faithfully preserves all required frame relationships.

| Classification | Work required | Architectural meaning |
| --- | --- | --- |
| Attention integration repair | Apply Attention authority and traceability to analysis before evidence selection, and preserve them through retrieval and serialization | Complete the intended integration; do not add a parallel context authority |
| Contract and diagnostic repair | Align stage instructions, schema routing, parsing, and validation; distinguish existing failure causes and honestly label budget units | Correct inconsistent implementation, not create new workflow authority |
| Proposed quality-policy refinements | Define stage-specific excerpt adequacy, post-compaction quality gates, canonical evidence representation, and binding-aware accounting | Specify additional measurable guarantees within Attention; decisions and implementation remain outstanding |

An analysis-stage projection cannot use the coding-frame constructor unchanged: the accepted plan and executable work package it requires do not exist until analysis completes. A stage-appropriate representation is an integration detail under the same Attention principles, not permission to invent plan authority or replace the Attention layer.

## Decision

Orchard will complete the existing Attention integration so that Attention relationships govern **quality-governed invocation context** from admitted authority and revision-pinned evidence. The frame or stage-appropriate Attention projection must control evidence selection and remain traceable through excerpting, compaction, envelope serialization, and provider formatting; merely embedding a frame alongside independently selected source is insufficient.

Quality is determined before inference by relevance, authority, semantic adequacy, consistency, representation cost, and reproducibility. It is not a single aggregate score or a model's assertion that it has enough context. The quality rules below refine faithful Attention compilation; they do not establish a separate context-quality authority or require a new orchestration subsystem.

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

- Repository analysis joins the intended Attention integration rather than acquiring a separate reasoning or context architecture.
- Context quality becomes an inspectable Orchard responsibility rather than prompt-engineering intuition.
- Valid coordinates and valid source hashes remain necessary but are no longer confused with semantic sufficiency.
- Smaller prompts do not automatically imply better reasoning; useful evidence must survive compaction.
- Larger model windows do not excuse irrelevant retrieval or inconsistent instructions.
- Canonical references reduce overhead while preserving full audit evidence outside the model window.
- Tokenizer and chat-format compatibility add implementation and versioning obligations.
- Legacy conservative budgeting remains available, but its uncertainty is observable and cannot be mislabeled as measured exhaustion.
- A blocked context can be repaired without rewriting historical authority, spending inference, or weakening acceptance.

## Alternatives Considered

### Introduce a Parallel Context Architecture

Rejected. The trace shows missing Attention integration and incomplete enforcement of materialized context, not that the governing Attention principles must be replaced. Repair the integration and specify its missing quality guarantees before considering another abstraction.

### Increase the Aperture First

Rejected as the default response. It can hide accounting defects, cost more, and leave relevance and contradictory instructions unchanged. An explicit increase remains legitimate when measured adequate context actually needs it.

### Cap the Number of Paths

Rejected as a quality rule. File count does not measure tokens, relevance, adequacy, or transversal scope coverage. Discovery limits must be reported separately and cannot silently narrow authority.

### Keep Only Smaller Excerpts

Rejected as a general solution. Headers and tiny fragments can fit while proving nothing about behavior. Compaction must preserve the evidence needed for the active decision.

### Let a Model Summarize or Score Its Own Context

Rejected as admission authority. A model may assist bounded investigation, but cannot grant relevance, invent missing relationships, or certify provenance and completeness. Deterministic quality reports precede inference.

## Open Questions and Completion Gate

The historical source-path trace is recorded below and establishes the missing analysis integration and the absence of a final semantic context gate in coding at that revision. It does not establish that the governing Attention design is inadequate. The integration repairs and their regression checks are recorded separately below. This proposal remains incomplete pending stage-specific quality-policy decisions and executable evidence for the additional semantic and accounting guarantees.

The trace addresses:

- Which model-backed stages actually compile and consume an Attention frame, and which construct independent context?
- Where are frame relationships, evidence-only dispositions, active/deferred coverage, and permissions retained, weakened, or omitted?
- Can later retrieval, envelope construction, or compaction add irrelevant material or remove necessary semantics despite a structurally valid frame?
- Which contract controls the exact request sent to the provider, and does it agree with the stage's parser and validator?
- What minimal changes and executable checks make the final request a faithful implementation of the frame rather than a parallel description of the same work?

Faithfulness requires an auditable mapping from every model-facing actionable item to frame authority, every active obligation to adequate evidence or an explicit unresolved disposition, and every transformed reference to pinned provenance. Structural frame validity alone does not establish these properties. Findings must distinguish observed code paths from proposed remedies and avoid attributing analysis failures to a coding-stage frame that may never have governed that invocation.

The current scope is context quality and faithful frame compilation. Model-resource scheduling, semaphores, resource leases, provider KV-cache swapping, and a general offline semantic adjudicator are not prerequisites for completing this decision.

## Implementation Trace: 2026-10-03

The following source trace was performed at `b70019b`, without live inference. It answers the code-path questions above; semantic acceptance criteria and their implementation remain proposed. The exact historical pre-inference prompt for attempt 646 is still unavailable, so this trace must not be presented as a replay of that prompt.

### Repository Analysis Is Not a Coding-Frame Consumer

Production wiring injects repository intelligence into analysis and injects analysis into the coding worker in [OrchardApplication](../../backend/src/main/kotlin/com/orchard/backend/OrchardApplication.kt#L333). The worker requires a current plan before coding when that production dependency is present.

The analysis path is:

```text
immutable run definition and coordinate admission
	-> compatible pinned intelligence manifest
	-> graph-local coordinate roots and neighbors
	-> pinned source excerpts and selector memberships
	-> required path selection
	-> independent analysis envelope and compaction
	-> provider request
	-> strict candidate decoding
	-> deterministic owner, scope, and verification compilation
	-> plan validation and persistence
```

[RepositoryAnalysisService.analyze](../../backend/src/main/kotlin/com/orchard/backend/analysis/RepositoryAnalysisService.kt#L485) does not create or consume `AttentionFrame`. The graph selection preserves coordinates and reports omitted paths and unresolved boundaries, but projects its selected paths into source collection; graph relationships and boundary diagnostics are not fields of `CodingRepositoryContext`. The analysis envelope instead serializes scope text, selectors/path groups, repeated evidence identities, criteria, commands, and retry diagnostics. It has no explicit actionable/evidence-only or active/deferred frame contract.

[Graph-local selection](../../backend/src/main/kotlin/com/orchard/backend/analysis/RepositoryIntelligenceGraph.kt#L150) defaults to 24 paths, favoring anchors before neighbors. `boundedRepositoryAnalysisPaths` separately takes 24 paths. These are context-selection limits, not the removed coordinate-admission cap. The graph trace records omissions, but the model envelope does not express the omitted authority as deferred obligations. This risk is separate from attempt 646, where all 23 required paths were represented.

[Envelope construction and compaction](../../backend/src/main/kotlin/com/orchard/backend/analysis/RepositoryAnalysisService.kt#L709) retains fixed authority fields while shrinking `repositoryContext`. `requiredEvidence` describes `authorityContext`, not necessarily the final excerpt set; presence in that inventory does not prove that usable source reached the model. The compactor checks required path availability, size, and nonempty content, can remove matched declarations, and has no semantic adequacy predicate. Its null results are all reported at this call site as budget overflow.

After inference, [candidate compilation](../../backend/src/main/kotlin/com/orchard/backend/analysis/RepositoryAnalysisService.kt#L1379) narrows source operations and constructs a plan. Subsequent owner, scope, and verification compilation and `validateOutput` enforce admitted criteria, path/scope relationships, operation shape, and commands before persistence. This is real downstream authority enforcement, but it does not establish that the preceding model input exposed adequate behavior evidence.

### Coding Frames Preserve Authority, Not Excerpt Sufficiency

The production coding path is:

```text
accepted execution plan and work definition
	-> executable work package with source and evidence authority
	-> coding Attention frame
	-> structural frame verification
	-> work-package ownership paths and plan/task lexical query
	-> fresh pinned source excerpts
	-> frame-bearing coding envelope
	-> provider request
	-> strict tool-batch decoding
	-> frame path/action drift check
	-> work-package application and verification
```

[CodingWorkerService](../../backend/src/main/kotlin/com/orchard/backend/agent/CodingWorkerService.kt#L350) builds the package, then compiles and verifies the frame before source collection. [Frame compilation and verification](../../backend/src/main/kotlin/com/orchard/backend/attention/AttentionFrame.kt#L103) preserve objective and purpose hashes, plan/package identity, active and deferred operation orders, correlations, ownership, constraints, invariants, non-goals, and checks. Evidence-only correlations carry citations and no mutation actions. Returned coding actions are checked by `attentionOperationDiagnostic` against actionable paths and allowed actions.

The verifier receives the frame, plan, package, and project purpose, not the newly excerpted repository context or final prompt. Its `adequate` result certifies structural checks, not source-semantic sufficiency. The code does not run a second frame-to-excerpt quality gate after retrieval or serialization.

[Coding prompt assembly](../../backend/src/main/kotlin/com/orchard/backend/agent/CodingWorkerService.kt#L539) selects source paths from `workPackage.ownership.paths`. It embeds the frame, but does not compile source selection from the frame's correlations, prerequisite evidence, or read-only citations. Consequently, read-only evidence can remain as a citation without its source bytes; prerequisite and deferred operations survive as orders, not complete expanded evidence relationships. These can be intentional bounded projections, but their adequacy is not currently checked. Package operation instructions and complete package sources are not serialized as a work package; the prompt uses the frame's behavior/criteria and freshly collected excerpts.

This is not proof that any individual framed coding invocation failed. It is proof that frame validity and final-input adequacy are separate guarantees in the current implementation.

### Excerpt and Provider Boundaries

[Pinned excerpt collection](../../backend/src/main/kotlin/com/orchard/backend/agent/CodingWorkspaceGateway.kt#L166) hashes full source, distributes byte allowances, invokes lexical excerpting, and can fall back to a source prefix when a focused excerpt is short or lacks an owner declaration. It accepts nonempty excerpts that fit serialized byte limits. [Lexical excerpting](../../backend/src/main/kotlin/com/orchard/backend/agent/CodingWorkspaceGateway.kt#L991) can select declaration lines without their bodies, includes lexical count summaries, and falls back to prefix lines. Neither stage takes frame evidence obligations as input. Path identity and a full-source hash therefore cannot certify preserved behavior semantics.

[Model token estimation](../../backend/src/main/kotlin/com/orchard/backend/vector/ModelExecutionProfile.kt#L184) returns UTF-8 byte length. Both analysis and coding use it to compare serialized prompts against token apertures; coding also derives a source-byte allowance from that calculation. The resulting fit checks do not repair excerpt adequacy.

[Provider request construction](../../backend/src/main/kotlin/com/orchard/backend/vector/RestModelProvider.kt#L223) forwards the assembled string as an Ollama prompt or a single OpenAI-compatible user message. Ollama may retry structured generation without the structured format, using the same prompt. No provider-side step restores frame relationships or missing evidence.

The analysis system prompt requests `RepositoryAnalysisCandidate`, and decoding expects that candidate. The envelope still names `RepositoryAnalysisPlanContent`. Importantly, [Ollama format selection](../../backend/src/main/kotlin/com/orchard/backend/vector/RestModelProvider.kt#L613) recognizes that stale marker but actually supplies candidate JSON properties. Thus the wire schema is not the old plan schema: the proven defects are contradictory envelope prose and schema routing by prompt substring, plus inconsistent path-limit wording. A typed stage contract should replace this accidental dependency. Strict service decoding remains required even when provider formatting is absent or falls back.

### Minimum Changes for Faithfulness

1. **Integration repair: compile the analysis stage's authority relationships before selecting evidence.** Reuse the Attention authority semantics, but do not fabricate a coding frame: its accepted plan and work-package dependencies do not exist before analysis. Represent requirements, pinned roots, reasoning permissions, unresolved relationships, and coverage/deferment without granting mutation authority.
2. **Integration repair: make the invocation projection control retrieval.** Map every source item to a correlation and an inclusion reason. Carry required contracts, producers/consumers, regression and read-only evidence where the active question needs them, rather than equating evidence selection with writable ownership. Canonical representation is a proposed refinement, not a prerequisite for restoring traceability.
3. **Quality-policy refinement: validate the materialized context after every lossy transformation.** Check reference resolution, source provenance, explicit coverage, required semantic anchors/windows, and the stage contract after excerpting and compaction. Distinguish sufficient evidence from unresolved evidence; do not pretend deterministic checks can prove every semantic judgment.
4. **Contract repair: use one typed output contract through serialization, provider formatting, parsing, and validation.** Preserve stage responsibilities and remove stale schema markers and contradictory limits. Validate the exact provider-message representation, not just the frame object.
5. **Diagnostic repair and accounting refinement: make budget and rejection evidence trustworthy.** Immediately distinguish failure causes and label conservative estimates honestly. Binding-aware token accounting and canonical evidence deduplication remain explicit policy refinements. Record a final frame-to-context mapping and typed failure report; report capacity only after accounting and adequacy are established.

The decisive tests are not only frame hash tests: a structurally valid frame with headers-only source must fail behavior adequacy; removal of necessary consumer/test evidence must fail or explicitly defer the affected obligation; evidence-only paths must not grant mutation permission; a schema-marker or provider-wrapper change must not change the intended contract; and large coordinate inventories must preserve explicit coverage across selection limits. Positive tests must show that adequate bounded contexts still dispatch without weakening authority.

### Verification and Remaining Uncertainty

Existing `AttentionFrameTest`, `CodingWorkspaceGatewayTest`, `CompanyCircuitTest`, and `ModelProviderCatalogTest` suites passed using `:backend:jvmTest` with those four test filters. They establish current frame invariants, pinned excerpt collection, budget compaction, and mocked provider behavior, not the proposed final-context quality guarantees. No live model request, aperture change, or retry authorization was performed.

The original ambiguity is now resolved: the failed analysis did not use a coding frame, and the coding-frame path has structural enforcement without a final semantic context gate. What remains to decide is the stage-specific evidence-adequacy policy and its measurable acceptance fixtures, how to expose unresolved dependencies and deferred scope without unnecessary prompt expansion, and the smallest shared compilation contract that preserves existing authority and historical replay. This ADR remains proposed until those decisions and implementation evidence are available.

## Integration Repair Status: 2026-10-04

The two missing integration pieces are implemented in the current code, without introducing another context authority:

1. **Analysis-stage Attention integration.** `compileRepositoryAnalysisAttentionFrame` derives objective identity, scope relationships, exact roots, and selector references from the admitted work definition before graph selection. It grants `READ_SOURCE` and `PROPOSE_ANALYSIS`, not coding mutation actions. Candidate/evidence-only classifications follow existing scope-operation policy; they are not a claim that every candidate path requires a change. Scope-related graph evidence and selector matches govern the source projection. The envelope carries the bound frame and recomputes its hash and explicit deferred/unresolved evidence after compaction. Candidate source paths and citations are checked against the final frame's supplied evidence before deterministic plan compilation.
2. **Frame-governed coding assembly.** `codingAttentionContextPaths` selects correlated owners, citations, and accepted prerequisite owners rather than only writable ownership. `codingAttentionContextQuery` uses active frame behavior and evidence, not deferred-plan instructions. The frame retains the accepted operation instruction, and its verifier rejects rehashed changes to that behavior. A final path-binding check rejects missing correlated source or unrelated collected paths. Extra read-only source does not expand work-package ownership or the existing output path/action guard.

Analysis instructions and the envelope now agree on `RepositoryAnalysisCandidate`. At this integration checkpoint, the Ollama adapter recognized current and legacy markers by prompt substring. The subsequent completion-gate work below replaces that routing with an explicit provider contract.

Regressions cover evidence-only source rejection, unrelated paths, prerequisite retrieval without mutation authority, explicit evidence deferral after compaction, deterministic evidence restoration, active instruction preservation, the actual analysis envelope, and the candidate wire schema. Focused tests and the full backend `:backend:jvmTest` suite passed. No live inference, model-aperture change, or retry authorization was performed. Historical definitions, plans, packages, and model journals are not rewritten.

At this integration-only checkpoint, byte-based estimation, metadata deduplication, and behavioral excerpt sufficiency were still outstanding. The following section records the subsequent bounded quality implementation. Neither checkpoint establishes that historical attempt 646 has been repaired or retried. Scheduling, resource swapping, and a general offline semantic adjudicator remain outside this work.

## Bounded Quality Implementation: 2026-10-04

The analysis and coding paths now apply the following policies within the existing Attention integration:

- **Binding-aware accounting.** `accountModelInput` counts serialized prompt content with JTokkit 1.1.0 ordinary BPE tokenization for recognized or explicitly configured `o200k_base` and `cl100k_base` bindings. Input admission adds a named 512-token provider-overhead reserve; output accounting adds none. Unknown encodings retain the explicitly labeled `UTF8_BYTE_UPPER_BOUND` fallback. Missing provider usage uses these helpers. Legacy byte-estimator callers outside these paths are not globally replaced.
- **Separate limits and provenance.** Ordinary analysis retains `analysis-input-allocation-v1:70-percent`; focused correction uses `focused-correction-input-v1:100-percent`. User apertures remain unchanged. Source collection has an independent serialized-byte guard rather than treating tokens as bytes. Analysis context selections record `tokenAccounting`, `componentAccounting`, `budgetPolicy`, `minimumEnvelopeAccounting`, `qualityPolicy`, and `evidenceDiagnostics`. Model observations optionally record `inputAccounting`. Absent new fields remain omitted from serialization, preserving historical checksums.
- **Declaration-preserving Kotlin excerpts.** The `complete-kotlin-declarations-v1` policy parses `.kt` and `.kts` with Kotlin PSI, selects complete declarations rather than import-only prefixes, and preserves original source-line markers during re-compaction. Abbreviated Kotlin evidence must contain a complete declaration or script statement without syntax errors in that node. Full-source hashes continue to identify the original pinned artifact, not the excerpt. Complete artifacts, including empty scaffold files, remain valid evidence of their actual contents.
- **Post-compaction adequacy.** Both stages reject empty truncated evidence, abbreviated unsupported code languages, and Kotlin excerpts without complete parsed evidence. Compaction must pass the same adequacy predicate before admission; fitting alone is insufficient. Missing required paths are reported as unavailable evidence. Analysis distinguishes an oversized tokenizer-counted metadata envelope, uncertain capacity under byte fallback, and inadequate required source when metadata fits.
- **Canonical supplied-source inventory.** Analysis uses `repositoryContext.files` as the canonical path/hash/excerpt table and `attention.correlations` for scope relationships. The repeated `requiredEvidence`, `requiredScope`, `requiredEvidencePathGroups`, and `requiredScopeEvidencePathGroupIds` model-facing inventories are removed. Deterministic compilation retains its authority-side checks; only supplied final evidence may support model citations.

These guarantees are deliberately bounded. Ordinary content tokenization plus a fixed reserve is not exact provider chat-template measurement; template compatibility and reserve sufficiency still require provider-specific validation. Component counts aid attribution but are not necessarily additive under BPE tokenization. The metadata-only envelope is a diagnostic baseline, not semantically adequate source. Complete parsed declarations do not certify requirement-specific producer/consumer coverage, type correctness, or universal behavioral sufficiency. Unsupported abbreviated code is rejected, while partial non-code documentation retains existing behavior. Canonical source inventory removes the identified redundant inventories, but fully interned authority/reference tables and a complete transformation-by-transformation quality report remain proposed.

The parser dependency is pinned to `kotlin-compiler-embeddable:2.2.20`. Its working environment factory uses a K1 API with a compiler warning scheduled to become an error in Kotlin 2.3; the embedded IntelliJ runtime also emits a deprecated `sun.misc.Unsafe` warning on JDK 26. Compiler/JDK upgrades must revisit this compatibility boundary rather than disable adequacy checks.

Regression evidence covers supported token counts versus bytes, explicit fallback and overhead reserve, analysis component provenance, final envelope deduplication, complete Kotlin bodies after long import preambles, rejected headers/partial bodies, empty complete artifacts, source-line preservation, coding output diagnostics, and journal replay compatibility. Focused suites and the full backend `./gradlew :backend:jvmTest --no-daemon --console=plain` gate passed. No live inference, retry authorization, aperture change, or historical-record rewrite was performed.

## Implementation and Validation

### Automated Completion Gate

Run the full ADR gate from the repository root:

```bash
./gradlew :backend:adrCompletionGate --no-daemon --console=plain
```

`adr056CompletionGate` is the ADR-specific task behind this entry point. The acceptance manifest is [056-completion-gate.json](056-completion-gate.json): all 14 distinct validation obligations below are mandatory. Under `integrated-runtime-proof-v1`, each criterion requires nonempty `tests` and `integrationTests` sets, and every registered test in both sets must execute and pass. A non-integrated implementation is void as completion evidence: a helper, parser, compiler, or schema can be correct in isolation while its production caller ignores it. Integration tests must exercise the real service/application/provider entry point and assert the resulting dispatch, admission denial, mutation boundary, durable record, or replay behavior. Constructor availability, a source import, or helper invocation alone is insufficient.

An empty acceptance or integration proof set, missing test, failed test, skipped test, duplicate required-test identifier, or missing criterion blocks completion. The report exposes `integrationStatus` separately from the combined criterion status. `partialTests` records narrower regression evidence but cannot grant a pass or be reused as declared integration proof. Test filtering and excluding `jvmTest` are forbidden; an up-to-date full-suite result remains usable through Gradle's input/output checks. Registered integration evidence is reviewed for the behavior it actually asserts, not merely its test name.

The task runs gate self-tests and the full backend suite, then writes `backend/build/reports/adr-completion/056.json` with per-criterion status and reason, partial evidence, regression failures, executed-test count, and a source/manifest/document snapshot hash. Backend test failures are collected for this report but still cause the completion gate to fail. Gate self-tests are part of ordinary backend `check`. Marking this ADR `Accepted` also makes the full ADR gate mandatory for backend `check`; acceptance cannot be justified by passing only the ordinary tests. A report is evidence of an invocation, not permission to adopt the ADR or rewrite historical records. Trust completion only after a successful gate command for the current snapshot.

Once the requested gate's task graph is ready, it replaces any previous report with `complete: false` and `gateRunState: NOT_COMPLETED` before task execution. An interrupted, filtered, excluded-test, or failed invocation therefore cannot leave an earlier completed report as its latest result. Excluding the ADR-specific gate from an explicit completion command is rejected. Only the full evaluator may replace that provisional record with `PASSED` or a detailed `BLOCKED` result.

The gate blocks whenever complete integrated proofs are absent. The acceptance manifest now pairs all 14 obligations with runtime proofs, including service admission and denial, final provider-request accounting, supplied/deferred coordinate coverage, read-only mutation rejection, and durable replay. A registered test name is not itself completion: the full gate must execute and pass for the current source snapshot.

The 2026-10-05 integration fixtures additionally prove headers-only source is rejected before inference by both analysis and governed coding, an exact admitted missing path is recorded as failed adequacy rather than overflow, and the failed-compilation report replays through a restarted file store/service with identical ledger bytes and no inference. Canonical-reference proofs cover both model-backed stages against admitted authority and durable source mappings. Quality reports retain separate dimensions; coding schema validity cannot imply downstream success when the Attention action guard denies the result. Known denial is `FAIL`, while later unresolved execution outcomes remain `UNKNOWN` and final coding results remain in the execution ledger. The acceptance manifest registers complete proofs only together with these integrated observations.

Provider routing uses `ModelOutputContract`: analysis selects its candidate contract through its stage entry point, and coding supplies an explicit generic-proposal, bounded-batch, or literal-replacement contract. Conflicting schema markers in source/prompt text cannot select another stage's wire schema. Versioned contracts supply hard array limits to stage instructions, provider schemas, and post-decoding service checks. Analysis rejects an oversized candidate before deterministic plan compilation; coding rejects an oversized batch or a non-literal operation in literal-only mode before application. Per-invocation ownership and action restrictions remain stricter independent admission checks.

### Runtime Acceptance: 2026-10-09

- Raw Ollama bindings use `raw-input-v1`: the exact dispatched prompt is counted with the matching tokenizer, with zero chat-template overhead. Automatic formatting without verified template accounting blocks before inference. A tokenizer-model mismatch uses the explicit byte upper bound. Catalog admission permits only the supported `tokenizer.model` and `tokenizer.encoding` metadata keys as exceptions to credential-key rejection.
- Native Ollama `gpt-oss:120b` and `gpt-oss:20b` bindings without an explicit input format select Orchard's versioned `gpt-oss-harmony-v1` formatter. It renders the complete single-user, no-tools Harmony payload before admission and sends it with `raw=true`, bypassing an unknown server template. The UTC date is stable for the provider instance and included in effective binding provenance; explicit saved format/date settings remain authoritative and the catalog is not rewritten. Other models and explicit automatic or unsupported formats remain unverified.
- Harmony input accounting separates original content from formatting overhead and persists the complete rendered payload's byte count and hash. It uses `FORMATTED_INPUT_TOKEN_UPPER_BOUND` for ordinary BPE counting of the entire rendered input plus a two-token optional-boundary allowance, not an exact special-token count; tokenizer uncertainty instead uses the complete rendered UTF-8 byte upper bound plus that allowance. Structured/plain fallback dispatches the identical rendered input. Real analysis-service admission exercises default saved-style bindings under unchanged explicit apertures, alongside raw, mismatched, unsupported and oversized denial cases.
- Real analysis admission demonstrates a metadata floor larger than the byte allowance but smaller than the tokenizer allowance, Unicode accounting, unchanged explicit profile settings, matching HTTP-request and durable accounting, and denial without inference when capacity cannot be established. The final dispatched prompt uses the same renderer as admission.
- Source identity, materialized excerpt integrity, and declaration retention are separate. The full-source hash remains pinned; the excerpt digest includes generated line annotations. Only a leading provenance-comment node on abbreviated Kotlin evidence is excluded from the original-declaration retention comparison, not from excerpt integrity or accounting. Original source comments and marker-like string contents remain evidence. Re-compaction preserves original coordinates without duplicate annotations.
- Pinned and ordinary Kotlin collection use declaration-aware excerpting and enforce the same adequacy diagnostic as analysis admission before returning context. Enum entries cannot be selected as standalone declarations; a complete enclosing enum remains valid evidence. Real pinned-Git regression tests reproduce entries crowding out a fitting method and verify its complete body, source identity and provenance survive collection through actual analysis-service dispatch. An unfit enclosing declaration fails before inference, with the explicit 24k/4k aperture unchanged.
- Analysis output contract `repository-analysis-candidate-v2` derives the full nested JSON schema from the candidate's serialization descriptors, including required citation fields, optional nullable symbols, and string-array items. The same schema is supplied in model instructions and native Ollama format requests, with stage array limits applied centrally. A real provider-request-to-service regression proves valid pinned citations create a plan, missing hashes fail decoding, and stale hashes fail provenance validation without mutation under unchanged 24k/4k apertures. Citation provenance rejection is recorded as downstream failure independently of schema validity. This is a bounded integration repair, not implementation of all proposed cross-component coherence obligations in ADR 049.
- Runtime compaction preserves complete producer, consumer, and regression declarations and their source identities. An oversized mandatory producer body blocks before inference instead of dispatching fitting but incomplete behavior evidence. These are bounded declaration-retention guarantees, not universal semantic or type-correctness proofs.
- Governed coding supplies admitted read-only README evidence with no mutation actions. A model write to that evidence is rejected before mutation; both the repository revision and file contents remain unchanged. Typed-limit integration also rejects oversized decoded batches before mutation.

The dated 2026-10-04 checkpoints above retain their historical reserve and implementation limitations; they do not describe the current raw-format accounting policy. No live inference, historical-attempt retry, user-aperture change, scheduling expansion, or historical-record rewrite is part of these fixtures.

The dated sections above record the completed integration repairs and bounded accounting, adequacy, and inventory changes. The broader validation targets below remain the completion gate for this proposal, not a claim that all are covered by the current implementation. Reassess measured adequate workloads before adding analysis slicing; representation optimization must not delay corrective integration.

Validation must cover:

Every point below requires its behavior to be integrated and exercised through Orchard's production runtime boundary. An isolated implementation or passing unit test does not satisfy any point by itself.

- analysis-stage Attention integration before retrieval, without fabricated coding-plan or work-package authority;
- final-request traceability to Attention relationships, not just the presence of a serialized frame;
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