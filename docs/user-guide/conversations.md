# Durable Conversations

The Conductor is Orchard's primary desktop workspace for discussing and directing several governed engineering objectives in one durable chronology. Conversation records are control-plane evidence; workspace, workflow, repository, audit, standards, and promotion records remain the owning authorities.

## Start and Restore a Conversation

Open the Conductor from the desktop mode selector. Orchard restores conversations from `~/.orchard/projects/workspace/conversations.jsonl`; creating a conversation when none exists does not create or mutate product authority.

Select an objective before sending a message when more than one objective could match. Orchard rejects ambiguous implicit routing instead of guessing. The desktop refreshes from a monotonic event cursor and restores messages, objective revisions, admissions, command results, and projected workflow activity after restart.

## Objective Controls

An objective begins as a proposal and must be explicitly admitted before mutating capabilities become available. Objective controls are revisioned and preserve their source message hash.

- **Pause** prevents analysis, coding, and audit workers from dispatching new work for the objective. It does not interrupt an external mutation already in progress or remove completed evidence.
- **Resume** returns the objective to the ready state. Dependencies, current objective state, repository authority, and service policy are checked again at dispatch.
- **Priority** orders eligible correlated workflow runs. It never bypasses dependencies, repository isolation, or machine-resource admission.
- **Dependencies** must refer to objectives in the same conversation and remain acyclic. A mutating command cannot dispatch until every dependency is complete.
- **Cancel** closes the conversational objective and prevents further background dispatch. Cancel an existing workflow through its exact `CANCEL_WORKFLOW` command so workflow authority records the cancellation as well.

## Command Admission

Model interpretation can propose a typed command but cannot admit it. The authority rail shows the capability, payload, command ID, and command hash. Admission applies only to that exact hash. A stale objective state, incomplete dependency, invalid cross-project reference, or changed authority causes dispatch to fail closed.

Read-only status and repository inspection commands do not require admission. Every capability declares its owning service, admission rule, projected result type, allowed objective states, and idempotency strategy.

## Project Setup

An empty Orchard authority root can be initialized without leaving the Conductor. Admit typed work-item creation for the project hierarchy, bind an existing local Git repository when needed, inspect the current genesis phase, advance each revision-pinned genesis submission, and admit the final unchanged genesis revision. Genesis and workflow records persist the exact source command reference so restart recovery cannot adopt a merely similar project phase or older workflow run.

For an existing repository, bind its canonical local path before admitting an `EXISTING_LOCAL` genesis. For a greenfield project, the company circuit creates its governed local repository from the admitted blueprint.

## Activity and Provenance

Workflow state changes are projected into the originating objective without copying domain authority. Conversation inference activity persists the execution profile and version, provider/model binding fingerprint, configuration hash, prompt/output hashes, token counts, latency, budgets, and machine-resource decision.

Long conversations remain durable in full. Model calls receive a bounded recent window plus source-linked summaries and current correlated authority instead of replaying the complete transcript.

## Operational Boundary

Local promotion remains an explicit admitted command and never pushes a remote. Detailed workspace and governance views remain available from the Conductor for inspecting evidence and diagnostics. The Milestone 10.2 Orchard-on-Orchard three-change replacement proof remains a separate acceptance exercise recorded in the roadmap and ADR 044.
