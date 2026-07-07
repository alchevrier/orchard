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
- `db/`: The internal database and compiled vector state managed by the Autumn backend.

The Autumn backend will use the Base Directory as its source of truth, watching `rag-shared` and `projects` for changes to hot-reload into the `db` vector store.

## Consequences
- The installation/startup routine must initialize this base directory if it doesn't exist.
- Backup, synchronization, or sharing of Orchard's "brain" is as simple as managing/versioning the `rag-shared` and `projects` folder in this OS-level directory.
- The Orchard runtime must be heavily configurable via environment variables or a config file to point to this `BASE_DIR`.