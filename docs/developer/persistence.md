# Persistence and Recovery

## Storage Roots

`OrchardPaths` initializes these roots:

```text
~/.orchard/
  db/
  policy-packs/
    toolchains/
  projects/
    workspace/
    repositories/
  rag-shared/
```

Current workspace authorities are stored in `~/.orchard/projects/workspace/`. Managed greenfield repositories and worktrees are rooted under `~/.orchard/projects/repositories/`. Launcher logs are stored under `~/.orchard/logs/` by the shell scripts.

Do not infer authority from directory names alone. The composition root passes explicit directories to every store.

## Store Families

### Workspace Journal and Snapshot

`FileWorkspaceRepository` uses:

- `workspace.journal.jsonl` as the authoritative append path;
- `workspace.snapshot.json` as a checksummed compacted projection; and
- `workspace.journal.corrupt-<timestamp>.jsonl` for a quarantined malformed tail.

Transactions have monotonic sequence numbers, format versions, and SHA-256 checksums over canonical serialized payloads. The store fsyncs appends. After a configurable number of transactions, it writes the snapshot atomically and truncates the journal. A failed compaction leaves the journal authoritative.

### Append-Only Ledgers

Most workflow authorities use checksummed JSONL, including:

- workflow runs, episodes, and events;
- work definitions and collaboration;
- design governance and product genesis;
- staged plans, proposals, and dispatches;
- repository analysis and coding execution;
- company control;
- engineering standards, scoped standards policy, remediation campaigns, and campaign resolutions.

The standards family uses separate ledgers under the workspace authority root:

- `engineering-standards.jsonl` for base standard revisions, scans, and backlog admissions;
- `standards-policy.jsonl` for overlay revisions and exception proposals, admissions, and revocations;
- `remediation-campaigns.jsonl` for campaigns and policy-aware evaluations; and
- `campaign-resolutions.jsonl` for terminal cases, proposals, and admissions.

`standards-policy.jsonl` is protected by `standards-policy.lock` and recoverable final-append replay. Overlay revisions and exception lifecycle events are append-only. Effective standards and exception states are projections, not mutable records.

Stores validate record sequence, payload checksum, business keys, and cross-record invariants during replay.

### Atomic JSON Configuration

Small current-value catalogs use JSON files, including:

- repository bindings;
- model provider catalog;
- model profile settings; and
- machine usage policy.

Writers should use temporary files, force data to disk where the store pattern requires it, then atomically replace the destination when supported.

## Recoverable JSONL Tails

`loadRecoverableJsonl` permits recovery only when decoding fails at the final nonblank record. It:

1. writes the invalid tail to `<name>.corrupt-<timestamp>.jsonl`;
2. atomically rewrites the original file with the valid prefix; and
3. returns the valid records.

An invalid record followed by another nonblank record is interior corruption and throws. This distinction prevents silent loss of committed history while tolerating a torn final append.

Not every store necessarily uses this helper. Verify the concrete store before relying on tail recovery.

## Schema Evolution

Serialized authority is a compatibility surface.

- Add optional fields with defaults when old records must continue to decode.
- Use `@EncodeDefault(EncodeDefault.Mode.NEVER)` when a new default field must not alter old canonical serialized payloads or hash calculations.
- Do not rename enum values, record types, or existing fields without a migration and compatibility tests.
- Preserve hash inputs exactly. A logically equivalent reserialization can still invalidate a stored checksum or proposal hash.
- Keep historical records immutable; represent new meaning with successor records or revisions.

Milestone 10.1 preserves old standards and campaign hashes by appending optional effective-policy, applied-exception, and policy-authority fields with `EncodeDefault.Mode.NEVER`. Do not make those defaults eagerly encoded: historical checksum replay depends on their absence.

A store change is incomplete without a test that loads records written by the prior schema when compatibility is required.

## Concurrency and Locking

File stores synchronize in-process mutations and use file locking where the authority can be accessed across service instances or processes. Preserve the established store's lock, append, fsync, and atomic-move sequence. Do not replace structured serialization with string editing.

Service-level idempotency is still required. A lock protects a write; it does not resolve a crash between a Git/workspace mutation and the next ledger append.

## External Repository Consistency

Persistence records frequently pin Git HEAD, content hashes, canonical diffs, plans, prompts, and policy revisions. A restored `~/.orchard` tree without the corresponding Git objects is incomplete.

For a consistent backup:

1. stop backend and desktop processes;
2. copy all of `~/.orchard`;
3. back up every externally bound local repository; and
4. retain Git history containing pinned revisions.

## Recovery Design Checklist

Before adding a durable operation, answer:

- What is the immutable source identity?
- Which hash/revision proves the candidate has not drifted?
- What external mutation may happen before persistence?
- How will restart detect that mutation without duplicating it?
- Is ordering deterministic when matching a batch?
- Which record proves admission, execution, acceptance, and promotion separately?
- What final append can be safely quarantined?
- Which old records must retain byte-equivalent canonical serialization?

See ADR 005 for storage architecture and the later workflow ADRs for authority-specific recovery rules.
