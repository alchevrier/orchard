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
