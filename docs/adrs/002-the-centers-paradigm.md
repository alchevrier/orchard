# ADR 002: The "Centers" Paradigm for System Architecture

## Status
Accepted

## Context
Traditional project management tools use simple lists, boards, or tables. Orchard is a full operating system for governed software delivery, which requires organizing human intent, autonomous agents, and outputs cleanly without overwhelming the user.

## Decision
We will structure the application architecture and UI around specific "Centers" of operation:
- **Architect Console**: Where human intent is defined, backlogs are prioritized, and tickets are generated and designed.
- **Coding Center**: Where repo-aware agents implement specific tasks.
- **Quality Center**: Where evidence is produced (testing, Playwright, benchmarks).
- **Disaster Center**: Where failure simulations and resilience testing are managed.
- **Knowledge Center**: Where RAG data, culture rules, and workflow prompts are stored.
- **Correlation & Reporting Centers**: For tracking SLAs and delivery outcomes.

## Consequences
- UI and agent scopes are strictly separated by workflow phase rather than traditional CRUD models.
- Agents assigned to the Coding Center do not cross-talk with the Architect Console directly, ensuring boundaries around execution vs. planning.
