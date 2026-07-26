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

## Provider Conformance

A provider response is admissible only after a terminal completion record and strict batch decoding. On 2026-07-26, Ollama 0.32.4's native proxy canceled an operation-shaped `qwen3-coder:30b` response after 72 generated tokens with `done:false`, while its local llama.cpp-compatible runner completed the same model request. Orchard may use the configured local compatible-provider boundary for such a replay, but must record the endpoint path and must never admit the proxy's partial response.