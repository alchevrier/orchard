# ADR 013: System RAG and Prompt Governance

## Status
Accepted

## Context
Orchard's LLM routing requires highly specific, rigid JSON generation rules to function correctly. Hardcoding these large system prompts directly inside Kotlin source files limits configurability, makes the system opaque to the human user, and requires compiling the entire backend just to tweak an LLM behavior rule.

## Decision
Orchard introduces the **System RAG** concept—a dedicated prompt governance layer.
1. **File-Based Prompts**: System prompts defining agent personas, JSON bounding rules, and FSM transition expectations are stored as Markdown documents in `~/.orchard/rag-shared/system-prompts/`.
2. **Default Templates**: The Orchard repository ships with default templates inside `backend/src/main/resources/default-system-prompts/`. On first boot, these are copied to the user's Orchard Base Directory.
3. **Mechanical Injection**: When an FSM phase (e.g., Architect Synthesizer Phase 2) executes, it looks up the specific prompt file offset natively, prepends it to the user's context, and pushes it to the Ollama gateway.

## Consequences
- Total transparency: The user can literally open `architect_phase2_planning.md` and see *exactly* how Orchard's core OS layer talks to the LLM.
- Extensibility: A user can rewrite the OS's internal workflows and governance constraints simply by editing a Markdown file, without touching Kotlin code.
- Shifts Orchard further towards an Agentic OS where behavior is completely data-driven by the filesystem.