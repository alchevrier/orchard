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
