# ADR 010: Formidable UX via Agentic Ticket Generation

## Status
Accepted

## Context
Orchard must provide an elite Developer Experience. Forcing the user to manually fill out forms to define Epics, Tasks, or Workflows defeats the purpose of an Agentic OS.

## Decision
We enforce a **Prompt-to-Intent** Generation pipeline inside the Architect Console.

1. **User Action**: The user chats naturally: *"I want a project called Aurora. It needs a React frontend and a Node backend. Make a ticket to setup the database, and write a workflow that all PRs must have 90% coverage."*
2. **Architect Agent**: The local LLM processes the chat stream. It does not output Markdown directly to the UI. Instead, the Agent synthesizes the user's intent into explicit JSON FSM Payloads (the `DocumentIntent` format).
3. **Execution**: The Agent automatically dispatches the multiple JSON arrays down the Autumn HTTP socket (`/api/documents`).

## Consequences
- The UX is entirely conversational and declarative.
- The UI never parses raw markdown directly from the chat box to the compiler. The Chat Agent acts as a translation layer, securely emitting standardized JSON boundary properties.
- The backend remains entirely oblivious to the "Chat" aspect. It simply receives perfectly formatted `DocumentIntent` packets on its `HttpGateway`.