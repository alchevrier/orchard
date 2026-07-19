# Troubleshooting and Data

## Quick Service Checks

Check the workspace API:

```bash
curl -fsS http://127.0.0.1:8085/api/workspace
```

The Architect server currently exposes a POST-only chat route. Check that its port is listening:

```bash
curl --silent --output /dev/null --write-out '%{http_code}\n' \
	-X POST -H 'Content-Type: application/json' -d '{}' \
	http://127.0.0.1:8086/api/architect/chat
```

An HTTP `400` confirms the Architect server is reachable and rejected the deliberately incomplete request without invoking a model.

Check Ollama when using the default provider:

```bash
curl -fsS http://127.0.0.1:11434/api/tags
```

If an API port is already occupied, `run_orchard.sh` attempts to reuse it only when it responds as the expected service. Stop conflicting software before retrying.

## Setup Problems

### JDK or Gradle Fails

Verify Java 21 or newer:

```bash
java -version
```

Then rerun the diagnostic setup:

```bash
./setup_orchard.sh --check
```

Use the Gradle wrapper from the repository root. A system Gradle installation is not required.

### Linux Desktop Does Not Start

Compose Desktop requires a graphical session and native libraries. Rerun `./setup_orchard.sh` on a supported distribution so it can install the package set, then start Orchard from that graphical session.

### Windows

The setup and run scripts do not automate Windows. Build and run through a JDK 21 environment manually, or use a supported Linux/macOS host. WSL does not by itself provide a supported Compose Desktop setup.

## Model Problems

### Ollama Is Missing or the Model Is Absent

For the default stack:

```bash
./setup_orchard.sh
./run_orchard.sh
```

The setup script detects the hardware tier and installs every model referenced by its preset. In execution settings, inspect the Ollama endpoint to compare configured bindings with the models currently installed.

If you intentionally use another provider, start with `./run_orchard.sh --skip-ollama` and configure that provider in Orchard.

### Strict JSON or Context Validation Fails

Confirm the binding declares strict JSON and enough context for its execution profile. Broad repository analysis requires substantially more context than bounded definition work. Also confirm the endpoint reports the exact configured model identifier.

### Requests Are Resource Blocked

Resource admission fails closed. Inspect machine policy, active concurrency, free memory, and the binding's declared resident memory and CPU percentage. Reduce concurrent work or deliberately revise policy instead of repeatedly submitting the same request.

## Workflow Problems

### Start Company Is Disabled

Product genesis must be admitted and in READY. Organization-governed genesis is intentionally blocked until verified policy source authority is implemented.

### Coding Does Not Start

Inspect the run and execution plan. Common blockers are an unbound repository, a dirty or drifting base revision, missing compatible staff, incomplete definition/design gates, no admitted plan, or an unavailable model/resource lease.

### Promotion Is Disabled or Fails

Promotion requires accepted evidence and audits, a clean destination, exact ancestry, and the expected candidate diff hash. Commit, stash, or otherwise resolve user-owned destination changes outside Orchard, then retry without rewriting Orchard's evidence.

### Conformance Scan Is Rejected

The bound repository must be clean. The scan, standard revision, and later backlog admission all pin exact revisions and hashes. If HEAD changed after scanning, run a fresh scan rather than admitting stale candidate work.

A `POLICY_CONFLICT` means applicable overlays could not compose, commonly because a narrower scope tried to disable a mandatory floor. Inspect effective policy and record a successor overlay; do not edit the ledger.

### An Exception Is Not Active

Inspect its projected state. `PENDING` has not been admitted or has not reached activation. `EXPIRED` and `REVOKED` no longer authorize findings. `SUPERSEDED` pins an older effective standard, while `INVALIDATED` means Git ancestry or content-hashed compensating-control evidence changed. Submit a new evidence-bound proposal when current authority genuinely requires one; do not extend or rewrite the historical record.

### Campaign Is Blocked or Escalated

Open its resolution case. `BLOCKED` means the admitted leaves are exhausted without complete resolution. `ESCALATED` indicates unknown/conflicting evidence or regression. Ask the Architect for a proposal, inspect it, and admit only the action you intend.

## Local Data

Orchard stores mutable authority under:

```text
~/.orchard/
```

Important areas include:

| Path | Purpose |
| --- | --- |
| `projects/workspace/` | Workspace snapshot, configuration, and append-only authority ledgers |
| `projects/repositories/` | Orchard-managed local repositories and isolated worktrees |
| `policy-packs/toolchains/` | Installed toolchain execution policy packs |
| `db/` | Derived local database state |
| `rag-shared/` | Derived shared retrieval state |
| `logs/` | Backend, frontend, and Ollama launcher logs |

The exact layout evolves by authority. Use the [Persistence and Recovery](../developer/persistence.md) reference before inspecting it programmatically.

## Back Up and Recover

1. Stop Orchard before taking a consistent backup.
2. Back up the complete `~/.orchard` tree together with bound Git repositories.
3. Restore both authority records and repositories so pinned revisions still exist.
4. Start Orchard and let stores replay their ledgers.

Do not hand-edit JSONL records, checksums, sequence numbers, hashes, or admitted revisions. Stores recover only a malformed final append where supported; corruption in the committed prefix fails startup by design.

## Logs

The launcher writes process logs under `~/.orchard/logs/`. Inspect the backend log for API startup, ledger replay, provider, and reconciliation failures. Inspect the frontend log for desktop launch failures, and the Ollama log only when the launcher started Ollama.

For a code-level issue, continue with [Developer Documentation](../developer/README.md).
