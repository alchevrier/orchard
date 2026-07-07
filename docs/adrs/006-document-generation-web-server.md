# ADR 006: Document Generation via Web Server

## Status
Accepted

## Context
Orchard needs a mechanism for the user and the agents to create the raw Markdown files representing intent (Workflows, Projects, Epics, Tickets, Tasks). While users can manually edit these files via the OS file explorer, the primary mode of generation will be the Compose UI interacting with the local Autumn backend.

## Decision
We expose a REST API within the Autumn backend (`/api/v1/documents`) that accepts JSON payloads containing the category, title, content, and optional project scope.

The `DocumentManager` service routes the document to the correct physical location:
- Global scope (`Workflow`, `Principle`) -> `~/.orchard/rag-shared/{category}/title.md`
- Project scope (`Epic`, `Ticket`, `Task`) -> `~/.orchard/projects/{projectId}/{category}/title.md`

## Consequences
- The creation of these files via the REST API will trigger the local File Watcher, immediately invoking the `MemoryCompiler` pipeline to make the newly designed ticket or workflow available in the Vector DB for agents.
- Enforces a predictable folder structure for human readability.