# Project Conversations

Orchard organizes desktop work around a project Inbox, a Project board, and durable canonical conversations. Conversation records are control-plane evidence; workspace, workflow, repository, report, audit, standards, and promotion records remain the owning authorities.

## Inbox and Project

The project workspace has two primary surfaces:

- **Inbox** follows meaningful report revisions, decisions, failures, evidence, and outcomes for one project. Use unread, action-required, subscribed, blocked, and completed filters to narrow the list. Selecting a revision shows its details and exact evidence references.
- **Project** is the Jira-like whole-project view of epics, stories, tasks, bugs, workflow state, and delivery controls. Use it to understand and organize the complete body of work.

The two surfaces are projections over the same project and ticket authority. Inbox reports do not copy or edit ticket state, and the Project board does not create separate chat state.

## Start and Restore a Conversation

Open a report or ticket thread from Inbox or Project. Orchard restores the exact conversation from `~/.orchard/projects/workspace/conversations.jsonl`; creating a conversation when none exists does not create or mutate product authority. A project-context conversation header returns directly to that project's Inbox or Project board.

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

## First Project Outcome

The repository baseline may infer current intent from revision-pinned evidence, but it cannot invent the future outcome a person wants. Inbox therefore offers the smallest action supported by current Genesis authority:

- Before `ADMISSION`, confirm the current product intent and record the first desired outcome directly. Orchard creates one epic and one revision-checked `ADMISSION` state; it does not require architecture fields to be invented first.
- At `ADMISSION`, explicitly admit that confirmed intent and first outcome. The project then becomes `READY` while architecture, blueprint, implementation, and verification remain governed work.
- During later incompatible phases, use the baseline's canonical thread to inspect the exact authority and propose the applicable transition.

Architecture, repository shape, sizing, and verification remain governed design and delivery decisions. The Inbox does not fabricate those authorities to accelerate onboarding.

## Repository Onboarding

Ask the Conductor to onboard either an absolute local folder or an HTTP(S) Git URL. Orchard materializes one exact `ONBOARD_REPOSITORY` proposal showing the source, project title, and whether it will create a project or bind an existing project. Review and admit that exact command before filesystem or workspace authority changes.

After the admitted command correlates successfully, Orchard opens that project's Inbox immediately. The multi-step setup card is not an onboarding gate. Orchard content-addresses every Git-tracked artifact and imports repository structure plus current Orchard authority into one revision-pinned intelligence graph. A pending repository baseline appears while Orchard interprets that graph asynchronously. Orchard publishes progressive immutable revisions for repository structure and architecture, recorded decisions and ADRs, tests and verification, and delivery and runtime evidence. Each revision is cumulative, so useful findings appear before the full baseline completes.

The graph is the centralized repository picture; bounded model context is only an interpretation aperture over typed graph paths and relationships. The baseline reports complete tracked-file coverage, the exact graph hash, and its node and edge totals. Findings require exact citations from the pinned graph stage context, and Orchard resumes at the next incomplete stage after restart. The completed report correlates architecture, decisions, verification surfaces, operational evidence, material risks, unresolved questions, and evidence-backed first-outcome opportunities without mutating Genesis authority.

When evidence is partial, conflicting, or absent, the report explains the gap, its delivery impact, and concrete evidence that could establish it. Verification gaps can call out missing test levels or methodology, fixtures and representative data, deterministic test commands, CI execution, or durable test evidence separately. Use the diagnosis action to open the report's canonical thread and plan the evidence work. Orchard pins that advisory request to the assessed repository revision and asks for governed scope, acceptance criteria, and verification. The remediation turn cannot admit commands or revise or control objectives; Orchard does not create tests or change project authority until you separately admit the resulting exact work.

Model, resource, repository-context, or schema failures appear as durable baseline diagnostic revisions instead of leaving the report silently pending. Orchard applies status-specific retry cooldowns, continues from the last durable completed stage, and retries immediately when the bound repository or Genesis revision changes. Transient capacity pressure is not labeled as user action required.

Examples:

```text
Onboard the local repository at /Users/me/Repositories/payments as a new project named Payments.
```

```text
Clone and onboard https://github.com/example/payments.git as Payments.
```

Local paths may point anywhere inside an existing Git repository; Orchard binds the canonical repository root. URL onboarding accepts credential-free HTTP(S) URLs and clones into `~/.orchard/projects/repositories/`. It disables interactive credential prompts, submodule recursion, and Git LFS smudging. It does not run repository scripts, builds, package managers, hooks, or setup commands.

Credentials must not appear in URLs or conversation text. Private remote endpoints use an environment credential reference during model onboarding; repository credential delegation is not part of this slice. Model download and runtime installation also remain explicit local setup operations.

## Reports and Subscriptions

Each report has a stable scope over a project, ticket or outcome, capability label, or repository area. A report revision is immutable and records its source identity, source revision, state, content hash, and evidence references. Marking a revision read changes only the user's read projection.

Subscribe from the report details using one of these modes:

- `IMPORTANT` follows significant changes.
- `MILESTONES` follows milestone transitions.
- `ACTION_REQUIRED` follows changes that need a decision or intervention.
- `ALL` follows every meaningful published revision.

Pause or resume a subscription without changing the report, ticket, repository, or evidence authority. User-created reports can choose a supported scope and an initial subscription mode when created.

## Canonical Threads

Every report and workspace ticket resolves through the project thread API to one durable canonical conversation. Reopening the same report or ticket, including after restart, opens the same conversation ID. Inbox and Project pass that exact ID into the durable conversation workspace instead of embedding another chat interface.

Replies can discuss evidence or propose typed conductor commands. A report item and its thread link cannot mutate domain state directly; any mutation still requires the owning capability, exact command proposal, and applicable admission.

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
