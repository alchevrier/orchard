# ADR 045: Project Inbox, Report Subscriptions, and Repository-First Entry

## Status

Proposed

## Context

Orchard can durably onboard a repository, conduct several objectives, project asynchronous activity, and display workspace entities on a project board. These capabilities currently appear as separate experiences. Repository onboarding leads into a setup sequence, the conversation is organized globally, and the project board is an inspector reached through a separate mode. A user must correlate setup state, conversation activity, ticket state, repository evidence, and delivery progress manually.

The target experience opens the project as soon as its repository is bound. Orchard derives a revision-pinned baseline in the background and communicates meaningful findings through an inbox. The project board remains the whole-project view, while each ticket or report item has one canonical thread through which the user can question, correct, prioritize, pause, admit, and follow work.

This introduces an authority boundary. Reports must remain useful after restart and support subscriptions, but they cannot become a second mutable copy of ticket, conversation, repository, or evidence truth.

## Decision

Introduce durable project reports as append-only communication projections over existing authority.

### Authority ownership

- Workspace entities remain authoritative for projects, epics, stories, tasks, bugs, hierarchy, and ticket state.
- Conversation records remain authoritative for messages, objective revisions, command proposals, admissions, executions, and activity.
- Repository assessments, workflow evidence, audits, and company records remain authoritative for their respective claims and outcomes.
- Reports reference those records by stable identity, revision, and hash. They do not copy mutable domain state and cannot mutate it directly.
- Subscriptions determine which meaningful report revisions appear in a user's inbox. They do not change the records being observed.

### Durable records

The report ledger stores checksummed append-only records for:

- `ProjectReport`, the stable identity and scope of one user- or Orchard-created report;
- `ReportRevision`, an immutable delivery of that report at one source revision;
- `ReportItem`, an addressable finding, decision, progress update, or outcome within a revision;
- `ReportSubscription`, an immutable successor describing whether and how the report is followed; and
- `ReportThreadLink`, the canonical conversation associated with a report or workspace ticket.

A report scope initially supports a project, workspace ticket, outcome, capability label, or repository area. Later map selections can compile to the same scope representation without changing report authority.

Every report revision records its source type, source identity, source revision or hash, creation time, and content hash. Every item records a stable item key, kind, state, title, summary, whether user action is required, and exact evidence references. Repeated publication of the same report/source revision is idempotent.

Subscriptions use immutable actor-scoped successor revisions. The initial modes are `IMPORTANT`, `MILESTONES`, `ACTION_REQUIRED`, and `ALL`. A subscription may remain active, pause, or close when the observed outcome completes. Notification transport is not authority; polling a durable inbox projection is sufficient initially.

### Canonical threads

A report or workspace ticket has at most one active canonical conversation link. The project Inbox renders that conversation inside the selected report or ticket content, so the transcript, objective controls, and exact command admissions remain in the same operating surface as the update they govern. Opening a board ticket resolves or creates that link, routes to the Inbox, and selects the same conversation used by its updates. Replies use the existing conductor and typed capabilities. A reply can propose or admit a domain mutation, but neither a report item nor a thread link performs the mutation itself.

Starting a project conversation creates one user-report envelope and resolves its one canonical conversation. The envelope supplies project, scope, subscription, and Inbox identity; the conversation ledger remains authoritative for the seed message and all replies. Orchard selects the new Inbox item immediately and does not create an unscoped global conversation as a side effect.

Thread identity is stable across restart. Reports may link several items to one ticket thread, but Orchard never creates duplicate conversations merely because another report revision references the ticket.

### Repository-first onboarding

After an admitted repository-onboarding command succeeds, the desktop opens the project workspace immediately. Orchard first imports every Git-tracked artifact into a content-addressed repository intelligence graph and correlates deterministic repository structure with Orchard project, work, Genesis, workflow, and evidence authority. The graph records explicit coverage totals and remains available through the project's read-only repository-intelligence API. Orchard publishes an initial baseline report in a pending state, then enriches the same report progressively from graph-pinned asynchronous analysis. The durable analysis advances through ordered structure and architecture, decisions and ADRs, verification and tests, and delivery and runtime stages. Each stage appends a cumulative immutable snapshot and survives process restart.

The graph, not bounded model context, is the centralized repository picture. Every tracked artifact is represented and content-addressed, including opaque assets that cannot enter a model prompt. Deterministic import derives modules, source declarations, imports, tests, ADR and document path references, build dependencies, and correlations to Orchard authority. Model stages consume typed graph slices plus exact source bytes and require multiple verified citations; a generic single-file presence observation is not a sufficient baseline. The baseline distinguishes supported, partially supported, contradicted, unestablished, and stale claims. It may infer the repository's apparent current intent, design, implementation, techniques, tests, reports, and evidence. It cannot invent future human intent. The user confirms or corrects the current understanding and defines the first desired outcome from the project inbox or board.

Partially supported, contradicted, and unestablished baseline findings project typed gap diagnoses into the report. A diagnosis states what is missing, why that limits outcome planning or delivery, and evidence that could close the gap. Verification diagnoses distinguish test level and methodology, fixtures and representative data, executable verification commands, CI execution, and produced evidence. Each diagnosis may open a revision-pinned remediation prompt in the report's canonical thread. These submissions are advisory: the conductor may discuss or propose governed work, but it rejects command admission and objective revision or control speech acts from the remediation turn. Remediation must identify scope, non-goals, acceptance criteria, verification commands, and unresolved decisions, and cannot mutate repository or project authority before a separate explicit admission.

Before Admission, that confirmation creates one revision-checked first epic and a deferred-design Admission revision. The user explicitly admits the confirmed intent and outcome before the project becomes Ready. Repository baseline assessment remains phase-independent and cannot mutate Genesis authority.

Compilation failures publish durable diagnostic revisions. Retry admission is derived from the latest diagnostic, uses status-specific cooldowns, and becomes immediately eligible when the repository or Genesis revision changes. Transient machine-capacity limits remain Orchard-owned operational state rather than user-required action.

Architecture, repository shape, sizing, and verification authority are deferred to governed design and delivery when work requires them. Standards that are not already authoritative may be reported as an action-required gap rather than blocking entry into the project.

### Desktop projections

The project workspace has two primary projections:

- `Inbox` lists durable report revisions and ticket updates using unread, action-required, subscribed, blocked, and completed filters.
- `Project` shows the Jira-like hierarchy and workflow state for all tickets.

Both projections resolve the same project and ticket IDs. Opening an inbox item focuses its report and renders its canonical conversation in the same detail surface. Opening a board ticket returns to the Inbox and selects the same canonical conversation. Updating work through that thread is visible on the board after the owning domain service records the change.

The inbox receives synthesized transitions, decisions, failures, and terminal outcomes. It does not expose every scheduler tick or model token event.

### Recovery and compatibility

The report ledger is replayed independently from domain stores and validates every monotonic event ID, report revision, subscription successor, content hash, and thread uniqueness constraint. A missing referenced source is projected as stale or unavailable; replay never fabricates replacement authority.

Existing projects and conversations remain valid. Projects without reports receive a baseline report lazily when first opened. Existing global conversations can be linked as canonical threads when an exact project or ticket correlation exists; ambiguous matches require a new explicit thread.

## Consequences

- Orchard gains an asynchronous product surface without weakening existing admission boundaries.
- The board, inbox, and conversations can evolve independently as projections while preserving one source of ticket truth.
- Baseline reports can provide immediate value before complete repository analysis finishes.
- Report publication and subscription projection add durable records and API surface that require replay, idempotency, and compatibility tests.
- The first implementation will use polling and concise text projections. Streaming transport, rich email rendering, architecture/UML maps, and generalized graph subscriptions remain later work.

## Acceptance Evidence

- Repository onboarding opens one canonical project and publishes one idempotent pending baseline report.
- A later repository assessment publishes an immutable enriched revision tied to the exact repository and Genesis revisions.
- A report, subscription, item identity, and canonical thread survive backend restart without duplication.
- A user can subscribe, pause, or resume report delivery without changing ticket or evidence authority.
- Opening a ticket from the board and its report item from the inbox resolves the same canonical conversation.
- Starting a project conversation creates one Inbox item and one canonical conversation, then restores that identity after restart without duplicating either record.
- A reply that proposes or admits work records its consequence through the existing conductor and owning domain service.
- Inbox and board projections resolve the same project and ticket state after restart.
- `ProjectInboxIntegrationTest` proves repository binding, pending and enriched baseline reports, first-outcome admission, workflow projection, subscription, canonical report and ticket threads, and fresh-instance replay in one scenario.

## Alternatives Rejected

- Treating conversation messages as reports: this loses report scope, subscription state, source revision identity, and deterministic inbox projection.
- Copying ticket state into report items: this creates conflicting mutable truth between the inbox and board.
- Blocking project entry until repository understanding is complete: this delays value and makes model or evidence limitations onboarding failures.
- Building the full correlation graph first: the graph should later connect proven reports, tickets, conversations, and evidence rather than become another isolated surface.