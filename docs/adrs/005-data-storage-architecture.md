# ADR 005: Data Storage and "rag-shared" Architecture

## Status
Accepted

## Context
Orchard requires a persistent storage mechanism for its vector database and the Markdown files that constitute its configuration and RAG knowledge base. We need a defined location so the application knows where to read the human-readable Markdown files, write the internal database files, and maintain the vector store.

## Decision
We define an "Orchard Base Directory" on the user's machine (e.g., `~/.orchard/` or an explicit OS app data directory). This directory serves as the definitive root for the local database and human-layer memory.

The directory structure will be:
- `rag-shared/`: The persistent engineering culture, global principles, known wrongs, and workflow rules. This exists as plain Markdown.
- `projects/`: Specific project contexts and specific localized memory.
- `db/`: Internal journals, snapshots, and derived vector indexes managed by Orchard.

The Orchard backend will use the Base Directory as its source of truth, watching `rag-shared` and `projects` for changes and rebuilding derived indexes beneath `db`.

Workspace entities and their relationships are structured state, not semantic memory. The persistence target is a filesystem-native store beneath `~/.orchard/db`, accessed through Orchard-owned repository interfaces. Its logical constraints mirror the deterministic Default Delivery workflow:

- Every Epic record references an existing Project record.
- Every Story record references an existing Epic record.
- Every Task or Bug record references an existing Story record.
- Every governed entity record carries a workflow ID referencing its workflow definition.

Accepted mutations will be appended to a workspace WAL before they update the in-memory structure-of-arrays projection. Startup replays that WAL, and periodic filesystem snapshots compact replay cost. The WAL and latest valid snapshot are the durable source of truth; vector files remain derived semantic indexes for retrieval, not the authority for IDs or hierarchy.

The filesystem layout beneath `~/.orchard/db` is:

```text
db/
├── workspace.wal
├── workspace.snapshot
└── vectors.wal
```

Records use a versioned binary envelope with checksums and monotonic sequence IDs. Writes use append, flush, and atomic rename where snapshots are replaced. The deterministic workflow validator runs before append and during replay; a corrupt, partial, or hierarchy-invalid record is never projected into live state.

Workflow rules may be authored as Markdown beneath `rag-shared`, but they must be compiled into deterministic policy structures before enforcement. Ollama translates natural language into proposed intents only. It is neither the database nor the final workflow authority.

## Consequences
- The installation/startup routine must initialize this base directory if it doesn't exist.
- Backup, synchronization, or sharing of Orchard's "brain" is as simple as managing/versioning the `rag-shared` and `projects` folder in this OS-level directory.
- The Orchard runtime must be heavily configurable via environment variables or a config file to point to this `BASE_DIR`.
- Workspace mutations require workflow validation and durable append acknowledgement before the in-memory resource projection is updated.
- Recovery replays only complete, checksummed, monotonically ordered records from the filesystem.
- Rebuilding the vector index must never change workspace identity or hierarchy.