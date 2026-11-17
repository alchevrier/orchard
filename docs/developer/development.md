# Development

## Toolchain

- JDK 21 or newer.
- Kotlin Multiplatform `2.1.21`.
- Compose Multiplatform `1.8.2`.
- Ktor `3.1.3`.
- `kotlinx.coroutines` `1.10.2`.
- `kotlinx.serialization` `1.8.1`.
- Gradle wrapper `9.3.0`.

Use the setup script on Linux or macOS, or install prerequisites manually:

```bash
./setup_orchard.sh --check
./setup_orchard.sh --skip-ollama
```

## Build and Test

Run the complete verification suite from the repository root:

```bash
./gradlew build --no-daemon
```

Run backend tests:

```bash
./gradlew :backend:jvmTest --no-daemon
```

Run one backend test class:

```bash
./gradlew :backend:jvmTest \
  --tests 'com.orchard.backend.standards.CampaignResolutionServiceTest' \
  --no-daemon
```

Run the full ADR 056 completion gate:

```bash
./gradlew :backend:adrCompletionGate --no-daemon --console=plain
```

This is separate from ordinary regression success. The gate requires all 14 criteria in [the acceptance manifest](../adrs/056-completion-gate.json), runs the full backend suite without filters, and emits `backend/build/reports/adr-completion/056.json`. Each criterion needs complete acceptance tests and explicit `integrationTests` through the real service/application/provider boundary. An isolated, non-integrated implementation is void as completion evidence. Missing, skipped, failing, or only partial evidence blocks completion. The ADR remains Proposed while this gate is blocked. Once its status is Accepted, backend `check` also requires the gate. Gate self-tests run as part of backend `check` regardless of adoption status. See [ADR 056](../adrs/056-quality-governed-model-context-compilation.md#automated-completion-gate) for proof boundaries and report provenance.

Run frontend desktop tests:

```bash
./gradlew :frontend:desktopTest --no-daemon
```

The test source sets are:

- `backend/src/test/kotlin` via `jvmTest`;
- `frontend/src/desktopTest/kotlin` via `desktopTest`.

## Run Components

Run the integrated local stack and desktop:

```bash
./run_orchard.sh
```

Run without launcher-managed Ollama:

```bash
./run_orchard.sh --skip-ollama
```

Run backend only:

```bash
./gradlew :backend:jvmRun --no-daemon
```

Run under an external human or AI pilot without background model dispatch:

```bash
ORCHARD_OPERATION_MODE=PILOTED ./gradlew :backend:jvmRun --no-daemon
```

`ORCHARD_OPERATION_MODE` accepts `AUTONOMOUS` (the default) or `PILOTED`. Piloted mode preserves read-only projections and explicit API actions while suppressing background model-backed analysis, coding, review, audit, campaign, and pending-command execution. Direct `jvmRun` does not start a model provider.

### Local Model Boundary Traces

Provider audit events normally retain hashes, counts and phases, not request/response payloads. To diagnose a new repository-analysis or onboarding attempt, opt in before starting the backend:

```bash
ORCHARD_OPERATION_MODE=PILOTED \
ORCHARD_MODEL_TRACE_DIR="$HOME/.orchard/diagnostics/model-boundaries" \
./gradlew :backend:jvmRun --no-daemon --console=plain
```

The trace directory must be an absolute, dedicated private directory on a POSIX filesystem. Capture is disabled when the variable is absent. Enabling capture does not authorize or start a retry; use the pilot's exact authorized action separately.

Each `analysis-<runId>-<UUID>.jsonl` file correlates the final `ANALYSIS_REQUEST`, prepared `PROVIDER_REQUEST`, UTF-8 `PROVIDER_RESPONSE` body, reconciled `MODEL_GENERATION`, `NORMALIZED_CANDIDATE`, `ANALYSIS_DECODE`, candidate validation and final `ANALYSIS_RESULT`. Provider request/response pairs have their own request IDs so structured/plain fallback remains distinguishable. Requests contain the actual serialized body, including the rendered prompt and schema, but not HTTP headers or resolved credentials. Ollama response capture preserves UTF-8 body text, including line endings, before stream reconciliation; this is not a network packet capture.

Onboarding uses `genesis-<projectId>-<UUID>.jsonl`, with `projectId` and a null `runId` rather than inventing a delivery run. `GENESIS_REQUEST` records phase, genesis revision, repository revision and the final logical prompt. Provider boundaries and `MODEL_GENERATION` precede `GENESIS_NORMALIZED_CANDIDATE`, `GENESIS_CANONICAL_CANDIDATE`, `GENESIS_DECODE`, successful `GENESIS_VALIDATION`, and `GENESIS_RESULT`. A failed decode records its diagnostic without filling missing citation fields. Proposal generation does not admit genesis authority. Onboarding's `genesis-proposal-v2` contract supplies a serializer-derived native Ollama schema specialized to required fields for the current phase (or assessment-only mode), and exact supplied path/hash pairs for support and defeaters. Other phases' submission fields can only be null or omitted. The prompt retains its phase-specific typed output example and explicit evidence requirements for unstructured fallback, without duplicating the full schema in the input budget. Independent phase, claim and pinned-source checks remain mandatory.

Payloads may contain source code and provider-generated text. They remain local, are not exposed through HTTP projections, and must not be uploaded or committed. Directories use owner-only permissions and files use `0600`. Each event retains at most 256 KiB of a UTF-8-safe prefix, with explicit truncation, original byte count and full-payload hash. Limits are 4 MiB per trace and 32 trace files; archive or remove specific old diagnostic files before exceeding those limits. No automatic deletion is performed.

Opted-in capture errors are logged and surfaced as `STORAGE_UNAVAILABLE`, not hidden as successful capture. A pre-dispatch capture failure prevents inference; a later failure cannot undo an already recorded plan or repository assessment. An onboarding terminal-capture failure identifies any assessment already stored, without admitting genesis. Missing terminal events identify interrupted or incomplete traces. Historical records containing only hashes cannot reconstruct an exact old prompt or response. The file-count limit is shared across analysis and onboarding traces.

To locate a failure, compare the response body with `MODEL_GENERATION`, then the normalized/decoded candidate, then the candidate check and final result. An invalid question present in raw provider output is a generation-side finding; a change introduced during reconciliation or decoding is an adapter/parser finding. Neither finding alone establishes a model-capability limit.

A `CREATED` response proves only the implemented schema, phase and citation checks, not that the proposal adequately describes the product. Review source coverage and the observations themselves before admission. Unexpected protocol text in an observation that is absent from the supplied source is not supporting evidence, even when its path/hash pair is valid. Do not silently strip such text or admit the proposal to make onboarding advance. Persisting a repository assessment does not accept classification or start delivery.

Genesis context reserves up to half of its existing 4 KiB source aperture for the opening of a tracked root README, then spends the remaining bytes and file slots on implementation evidence. The README comes first so prompt fitting does not discard product purpose ahead of code details. GPT-OSS onboarding uses native `think: "low"` in structured mode, as its plain fallback already does; other stages retain their existing policy and output budgets are unchanged. Claim observations containing Harmony protocol tokens absent from their cited excerpt are rejected before storing an assessment. Actual protocol source can still be cited.

Run the desktop only after the backend is ready:

```bash
./gradlew :frontend:desktopRun --no-daemon
```

The backend binds `127.0.0.1:8085`. Avoid parallel backend test/manual processes that attempt to own that port.

## Change Workflow

1. Identify the owning authority and its nearest invariant test.
2. Read the relevant ADR and store format before changing serialized data.
3. Make the smallest behavior change at the owning service/store boundary.
4. Run the narrow test immediately.
5. Add API and frontend changes only when the backend contract is stable.
6. Run module tests, then the full build.
7. Run `git diff --check` and inspect the final diff.
8. Update user/developer docs, ADRs, and roadmap state where applicable.

Do not regenerate or reformat unrelated files. The repository can contain user-owned uncommitted work; preserve it.

## Testing Expectations

Scale tests to the authority being changed:

- stores: replay, checksum, sequence, duplicate admission, corruption, and backward compatibility;
- services: valid transition, stale source, invalid candidate, idempotency, and interrupted recovery;
- model boundaries: strict envelope parsing, complete coverage, identifier/hash validation, and context budget;
- repository operations: clean/dirty state, ancestry, canonical path, diff hash, and command evidence;
- API: request decoding and status mapping;
- frontend client: serialized request/response contract.

Prefer temporary directories and temporary Git repositories. Tests must not depend on a developer's `~/.orchard` state or live model server.

## Documentation Checks

Documentation is plain Markdown and has no dedicated generator. Before finishing:

```bash
find docs -type f -name '*.md' -print | sort
git diff --check
```

Check repository-relative links, commands, route names, and milestone claims against source. Any new documentation index should use stable domain terms because repository context selection is query-ranked.
