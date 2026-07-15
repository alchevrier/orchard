# ADR 003: Memory Compilation Architecture (Markdown to Vector)

## Status
Accepted

## Context
Orchard requires a persistent memory for specific contexts, localized knowledge, tickets, and workflow designs. Storing everything in black-box LLM context windows or complex, unreadable JSON blobs prevents users from managing, reviewing, or version-controlling the actual intelligence of the system. 

## Decision
We establish a **Memory Compilation Architecture**:
- **Source Code (Human Layer)**: All project context, ticket designs, agent assignments, and workflow instructions are written and managed as plain Markdown files. These are editable via the UI, human-readable, and Git-versionable.
- **The Compiler**: A coroutine-based pipeline (`File Watcher -> Semantic Chunker -> Embedding API -> Vector Index`) watches the local workspace and rebuilds derived retrieval state from authoritative Markdown.
- **Compiled Binary (Agent Layer)**: Agents query the vector store, retrieving only highly-relevant, compressed chunks rather than full documents, keeping their LLM context windows lean, fast, and focused.

## Consequences
- Requires running a local file watcher and maintaining a rebuildable file-backed vector index behind Orchard-owned repository interfaces.
- Ensures the user is never locked out of their AI's reasoning: to change how the agent behaves, they simply edit or commit a change to a `.md` file.
