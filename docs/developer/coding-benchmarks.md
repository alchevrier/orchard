# Coding Benchmarks

Orchard evaluates coding capability with fixed, revision-pinned tasks and immutable `CodingBenchmarkResult` records. A benchmark suite must use identical intent, design, repository revision, checks, and scoring rules across bindings and tooling modes.

## Required Matrix

Each task records these four cells before comparison:

| Model tier | Tooling mode |
| --- | --- |
| Small | Minimal |
| Small | Full Orchard |
| Large | Minimal |
| Large | Full Orchard |

Run the suite on the declared 16, 32, 64, or 128 GiB hardware profile. Record the exact binding fingerprint rather than relying on a model display name.

## Metrics

Record completion, attempts to compile, repair count, fabricated references, scope violations, changed lines, elapsed time, input and output tokens, peak memory, escalations, candidate PR claim accuracy, and intent alignment. Claim accuracy and intent alignment are independent scores in the range zero through one.

Minimal mode supplies the same accepted intent and design but omits executable work packages, bounded tools, persistent repair, and PR-centered review. Full Orchard mode enables those facilities. This isolates the contribution of Orchard from raw model capability.

## Typography Replay

The fixed `typography-v1/remove-serif` task removes all six explicit `FontFamily.Serif` uses from the admitted production file and adds substantive production-bound regression coverage in the admitted test file. Inject one compile failure into the first candidate. A successful Full Orchard run repairs that failure on a descendant commit under the same work package and execution plan, runs every admitted check, and produces a candidate PR.

Do not download or install a model as part of a benchmark run. Model installation is explicit machine setup. If a required tier is unavailable, record the matrix as incomplete rather than substituting a larger model.

## Attention Planning Replay

The fixed `attention-planning-v1/candidate-pr-correction-lineage` task is derived from Milestone 10.2.2. It asks a model to plan the remaining proof that compile, test, code-review, and intent-review defects remain in one immutable candidate lineage and receive bounded successors without broad repository reanalysis.

The repository already contains parent candidate linkage, append-only dispositions, typed reviews, target-specific correction dispatch, work-package recompilation, design-revision requests, clarification and escalation blocking, and coding-worker candidate recovery. Those eight production owners are explicit `EVIDENCE_ONLY` correlations. The self-contained correction integration test is the only `ACTIONABLE` owner because the missing milestone evidence is a complete public-API and restart scenario joining the existing authorities.

The planning comparison uses the same objective, invariants, repository-owner catalog, model binding, seed, temperature, output schema, and token limits. Minimal mode receives a flattened task and repository summary. Full Orchard mode receives the compiled Attention graph.

The deterministic scorer rejects:

- missing required owners or scope dimensions;
- paths outside the fixture's owner graph;
- mutation of owners classified as compliant evidence-only authority;
- CREATE, MODIFY, or DELETE actions that disagree with owner authority;
- operations without an evidence-producing verification method; and
- claims that work is implemented, verified, passed, deployed, migrated, or accepted during planning.

Before scoring, Orchard may normalize authority-owned fields for an exact admitted path: requirement ID, scope kind, allowed action, expected behavior, and verification method. Every corrected path and field is reported. Unknown paths, duplicate owners, and missing owners are never repaired because doing so would require inventing authority.

A complete plan classifies all nine owners correctly, mutates only `SelfContainedCorrectionIntegrationTest.kt`, names the admitted evidence method for every authority, adds no unrelated path, and makes no lifecycle-state claim. Planning completion means the plan graph is adequate for admission; it does not mean the feature is implemented or verified.

The formal run must persist both raw responses, field-level normalization records, scorer results, binding fingerprint, and model execution provenance. The primary score is computed before normalization. The normalized score and correction count measure deterministic recovery cost separately.

### Initial Milestone Observation

On 2026-09-12, `qwen3-coder:30b` ran both milestone planning conditions with temperature 0, seed 42, the same nine owner facts, dispositions, actions, behavior, verification methods, JSON schema, and output budget.

- The flattened condition returned 8 of 9 exact owner paths. It classified all eight existing production authorities as `ACTIONABLE` `MODIFY` work, omitted `SelfContainedCorrectionIntegrationTest.kt`, and used the integration test's Gradle command as a repository path. Its named verification methods also omitted the admitted commands. It used 657 prompt tokens and completed in approximately 10.60 seconds.
- The Attention condition returned all 9 exact owner paths and correlations. It preserved all eight production owners as `EVIDENCE_ONLY` `READ_SOURCE`, selected only `SelfContainedCorrectionIntegrationTest.kt` as `ACTIONABLE` `MODIFY`, retained every admitted verification command, added no unrelated path, and made no implementation or verification claim. It used 1,050 prompt tokens and completed in approximately 9.85 seconds.
- Deterministic normalization cannot repair the flattened result because the required integration owner is missing and the command-shaped path has no admitted authority. The Attention result satisfies the primary graph score without authority repair; textual punctuation in behavior descriptions is non-authoritative.

This single controlled run is evidence that Attention can prevent a local model from turning already-compliant production authorities into unnecessary implementation work. It is not a completed model matrix or proof that every milestone plan will improve.

## Provider Conformance

A provider response is admissible only after a terminal completion record and strict batch decoding. On 2026-07-26, Ollama 0.32.4's native proxy canceled an operation-shaped `qwen3-coder:30b` response after 72 generated tokens with `done:false`, while its local llama.cpp-compatible runner completed the same model request. Orchard may use the configured local compatible-provider boundary for such a replay, but must record the endpoint path and must never admit the proxy's partial response.