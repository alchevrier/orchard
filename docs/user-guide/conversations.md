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

## Repository Onboarding

Ask the Conductor to onboard either an absolute local folder or an HTTP(S) Git URL. Orchard materializes one exact `ONBOARD_REPOSITORY` proposal showing the source, project title, and whether it will create a project or bind an existing project. Review and admit that exact command before filesystem or workspace authority changes.

Examples:

```text
Onboard the local repository at /Users/me/Repositories/payments as a new project named Payments.
```

```text
Clone and onboard https://github.com/example/payments.git as Payments.
```

Local paths may point anywhere inside an existing Git repository; Orchard binds the canonical repository root. URL onboarding accepts credential-free HTTP(S) URLs and clones into `~/.orchard/projects/repositories/`. It disables interactive credential prompts, submodule recursion, and Git LFS smudging. It does not run repository scripts, builds, package managers, hooks, or setup commands.

Credentials must not appear in URLs or conversation text. Private remote endpoints use an environment credential reference during model onboarding; repository credential delegation is not part of this slice. Model download and runtime installation also remain explicit local setup operations.

## Model Routing

The Conductor can inspect model endpoints, bindings, endpoint health, and current workload assignments. It can then propose exact admitted commands to register a model and assign it to one execution profile:

- `bounded-conversation-conductor-v1` for conversation interpretation;
- `bounded-definition-reasoning-v1` for requirements and work definitions;
- `bounded-circuit-synthesis-v1` for architecture and staged-plan design;
- `broad-repository-analysis-v1` for repository analysis;
- `bounded-coding-patch-v1` for coding; and
- `bounded-independent-audit-v1` for independent audit.

For example, ask Orchard to inspect model configuration, register an already-running local LM Studio or Ollama model, then assign its binding to `bounded-coding-patch-v1`. Orchard rejects bindings that cannot satisfy the profile's context window or strict-JSON capability. Remote provider secrets remain in environment variables referenced as `env:NAME`; only the reference is persisted or shown to the model.

## Activity and Provenance

Workflow state changes are projected into the originating objective without copying domain authority. Conversation inference activity persists the execution profile and version, provider/model binding fingerprint, configuration hash, prompt/output hashes, token counts, latency, budgets, and machine-resource decision.

Long conversations remain durable in full. Model calls receive a bounded recent window plus source-linked summaries and current correlated authority instead of replaying the complete transcript.

## Operational Boundary

Local promotion remains an explicit admitted command and never pushes a remote. Detailed workspace and governance views remain available from the Conductor for inspecting evidence and diagnostics. The Milestone 10.2 Orchard-on-Orchard three-change replacement proof remains a separate acceptance exercise recorded in the roadmap and ADR 044.
