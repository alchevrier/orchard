# ADR 014: MVP Scope - The Four Pillars of the Agentic OS

## Status
Accepted

## Context
To prevent scope creep and ensure we deliver a functional, high-leverage productivity tool, we must explicitly bound the Minimum Viable Product (MVP) of Orchard.

## Decision
The MVP represents a massive productivity lever by automating the administrative overhead of software delivery. It is strictly bounded to these four pillars:

1. **Agentic Jira (The Architect Console)**
   - A single chat interface that parses natural language intent.
   - Outputs tasks organized mechanically by Epic and Workflow.
   - Visualized in the Compose frontend as a strict Kanban/JIRA-like board driven by FSM states.
2. **Unified RAG (System & File Memory)**
   - Statically maps `~/.orchard/rag-shared/` (organization-wide culture/rules).
   - Statically maps `~/.orchard/projects/{id}/` (project-specific context).
   - Driven entirely by zero-allocation pipelines compiling Markdown to Vector DBs on the background thread.
3. **Seamless Hand-off (The Coding Center)**
   - The FSM allows a user to approve a generated ticket.
   - The OS routes the ticket bounds natively to specialized, offline LLM agents (via Ollama).
   - The agents attempt iterative file writes locked to specific workspace boundaries.
4. **Governed Evidence (The Quality Center)**
   - Every completed sub-task triggers a strict verification FSM loop.
   - Full reporting generated per-PR/Branch encompassing: Test Coverage (%), Benchmarks executed, and Proof of Workflow compliance.

## Consequences
- Unrelated GUI flashiness, complex user account management systems, or distributed cloud connectivity are out-of-scope for the MVP.
- The entire system relies on local filesystem structures and localhost daemons (Ollama).