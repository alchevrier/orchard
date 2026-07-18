# ADR 044: Durable Multi-Objective Conversational Conductor

## Status

Proposed

Implementation is present and validated in the Milestone 10.2 worktree. Acceptance remains pending until the three-change Orchard-on-Orchard replacement proof reaches local promotion and records the required intervention, model, timing, failure, evidence, and bypass reports.

## Context

Orchard already owns durable product genesis, work definition, repository analysis, coding, verification, independent audit, acceptance, promotion, conformance, remediation, resolution, and exception authorities. The current Architect chat does not conduct those authorities. It accepts one request-local prompt, serializes all requests through one global mutex, interprets a bounded create intent, mutates the workspace immediately, and returns a workspace snapshot. It has no conversation identity, message history, objective lanes, resumable context, command admission, or correlation to downstream authority.

The product should not become a conventional coding chat that performs untracked edits. Its opportunity is stronger: one long-lived conversation should let a person discuss, investigate, propose, admit, start, pause, resume, redirect, and observe several engineering objectives while Orchard preserves the authority and evidence boundaries already implemented.

A long-lived user experience cannot mean replaying an unbounded transcript into every model call. A single conversation can also contain unrelated objectives, ambiguous references, parallel execution, and results produced asynchronously after the initiating request returned. Orchard therefore needs a durable conversational control plane and a bounded context compiler, not a larger prompt attached to the existing endpoint.

## Proposed Decision

Introduce a `ConversationConductor` as a new authority and orchestration boundary above existing domain services.

### Durable conversation authority

Persist checksummed append-only records for:

- conversations with stable identity and lifecycle;
- user, assistant, and system messages with sequence, content hash, actor, and optional reply reference;
- objective revisions with project, repository, title, outcome, constraints, priority, dependencies, and lifecycle;
- typed command proposals derived from exact source-message hashes;
- explicit command admissions where mutation authority is required; and
- command correlations to the exact downstream records, IDs, hashes, and repository revisions produced by owning services.

Conversation records prove what was said, proposed, admitted, and correlated. They do not replace the domain records they reference. Genesis remains genesis authority; work definitions remain execution intent; repository plans remain coding authority; company records remain audit, acceptance, and promotion authority; standards records remain policy and conformance authority.

Client-generated message keys make retries idempotent. Per-conversation sequence validation preserves a total conversational order. Objective revisions are immutable successors rather than mutable rows. A crash after a downstream mutation but before conversational correlation is recovered by matching the admitted command key to existing domain authority before retrying.

Every mutating command has a stable `conversationCommandId`, command hash, capability ID, and source objective revision. Downstream records created through a conductor adapter persist an optional `ConversationCommandReference`, encoded compatibly when absent, or expose an authority-specific deterministic adoption key where schema extension is impossible. The adapter appends `DISPATCH_INTENT` before invoking the owning service and `CORRELATED` only after resolving the exact downstream ID and hash. Recovery never guesses from similar titles, timestamps, or model text.

### Objective lanes

One conversation may own several objective lanes. An objective has one of these projected states:

- `CANDIDATE`;
- `AWAITING_ADMISSION`;
- `READY`;
- `ACTIVE`;
- `PAUSED`;
- `BLOCKED`;
- `COMPLETED`;
- `CANCELLED`; or
- `SUPERSEDED`.

Objectives may reference other objectives as dependencies. The dependency graph must be acyclic. Commands within one objective are ordered through an objective-scoped lock. Independent objectives may progress concurrently under existing machine-resource admission and repository isolation. There is no conversation-wide execution mutex.

The current globally serialized analysis, coding, audit, and resolution workers must evolve to select or accept an eligible run or case and lock by that narrow owning identity. Concurrency is permitted only when repository worktrees, destination mutation, and resource leases are independent. Promotion into the same destination repository remains serialized and revalidates ancestry. Objective concurrency is not satisfied merely by displaying several states while a hidden global worker mutex prevents independent progress.

Pause prevents new work from being dispatched for that objective; it does not erase already produced evidence or forcibly interrupt an unsafe external mutation. Resume revalidates repository, policy, dependencies, and referenced authority before dispatch. Cancellation uses existing workflow cancellation semantics and never rewrites completed authority. Priority affects eligible scheduling order but cannot bypass dependencies or resource policy.

### Conversational interpretation

A provider-neutral conversation profile produces a strict candidate speech act from each user message. The closed initial vocabulary is:

- `DISCUSS`;
- `INSPECT`;
- `PROPOSE_OBJECTIVE`;
- `REVISE_OBJECTIVE`;
- `PROPOSE_DOMAIN_ACTION`;
- `ADMIT_DOMAIN_ACTION`;
- `PAUSE_OBJECTIVE`;
- `RESUME_OBJECTIVE`;
- `CANCEL_OBJECTIVE`;
- `SET_PRIORITY`;
- `REQUEST_STATUS`; and
- `CLARIFY`.

The host validates referenced conversation, message, objective, project, repository, proposal, and domain authority. Discussion and bounded read-only inspection may execute without mutation admission. A model cannot admit its own objective or domain action. A user message can serve as explicit admission only when it unambiguously references one already materialized exact proposal; otherwise Orchard asks for clarification.

The conductor invokes typed adapters for existing services. It does not call repositories or stores behind those services directly and does not reinterpret proposal prose as mutation authority.

Adapters are registered in a closed capability registry. Each capability declares its typed payload, read or mutation class, owning service, admission rule, idempotency strategy, allowed objective states, and projected result type. The model selects only a capability ID and candidate payload from the supplied registry. It cannot invent routes, service methods, shell commands, HTTP bodies, or generic tool arguments.

### Bounded context reconstruction

Each model call receives a deterministic context manifest containing only the relevant:

- conversation and objective identities;
- recent message window;
- source-linked objective summary revision;
- admitted constraints and unresolved questions;
- correlated downstream authority and current states;
- revision-pinned repository evidence selected for the requested operation; and
- token, provider-policy, and resource budgets.

Objective summaries are derived navigation artifacts. Every statement must cite source message or authority IDs, and summaries can be regenerated from the append-only ledger. They never replace original messages or domain records.

The complete transcript remains inspectable, but the compiler must prove that a conversation larger than the selected model context remains operable without truncating active authority. Provider changes cannot alter conversation or objective identity.

### Asynchronous projection

Existing workers continue to own background analysis, coding, audit, campaign, and resolution progression. The conductor projects their durable state into objective activity events correlated to source authority. It emits an assistant response when a meaningful transition, question, failure, admission requirement, or terminal result needs attention.

Polling with a monotonic event cursor is sufficient for the first implementation. Streaming transport is not required for correctness. Repeated projection after restart must not duplicate messages or downstream actions.

### Desktop control surface

The desktop presents one chronological conversation with an objective rail. The operator can:

- create or select a conversation;
- see which objective each message and activity belongs to;
- inspect active, paused, blocked, awaiting-admission, and completed objectives;
- review exact candidate actions before admission;
- switch objective focus without losing chronology;
- set priority and dependencies;
- pause, resume, redirect, or cancel valid work; and
- open the existing detailed authority projections for evidence and diagnostics.

Existing project and governance panels remain inspectors and precise fallback controls. The conversation becomes the primary conductor, not the sole representation of truth.

### Initial implementation map

The backend adds dedicated conversation authority, context compilation, conductor, capability registry, and activity projection modules. The initial durable files under the workspace authority root are:

- `conversations.jsonl` for conversation, message, objective revision, command proposal, admission, dispatch, correlation, summary revision, and projected activity records; and
- `conversations.lock` for cross-instance append exclusion.

If activity volume later justifies a separate ledger, that is a compatible storage revision rather than a reason to weaken the initial single replay order.

The first typed workspace API is:

- `POST /api/conversations` to create a conversation;
- `GET /api/conversations` to list projections;
- `GET /api/conversations/{conversationId}?afterEventId=` to restore or incrementally refresh chronology and objectives;
- `POST /api/conversations/{conversationId}/messages` with `clientMessageId`, expected conversation sequence, content, and optional explicit objective ID;
- `POST /api/conversation-commands/{commandId}/admission` to admit one exact command hash; and
- `POST /api/conversation-objectives/{objectiveId}/control` for typed pause, resume, cancellation, priority, and dependency changes.

UI focus is convenience, not authority. A message request carries an objective ID when the operator selected one. Without an explicit target, the host accepts implicit routing only when deterministic conversation state identifies exactly one applicable objective; otherwise it records `CLARIFY` without dispatch.

The current `POST /api/architect/chat` direct prompt-to-workspace mutation path is deprecated during the ledger slice. The desktop migrates to conversation APIs before domain adapters expand. At milestone completion there is no production route that can bypass command identity and correlation; compatibility behavior must enter a named legacy conversation through the same conductor or fail with an explicit migration response.

The first vertical capability set is deliberately narrow:

1. discuss and report status;
2. inspect revision-pinned repository context;
3. propose and admit one bounded work objective;
4. produce or revise its work definition and staged plan;
5. start and cancel its existing workflow;
6. observe repository analysis, coding, verification, audit, acceptance, and promotion; and
7. answer from correlated evidence.

Only after this one-objective path survives restart and reconciliation are additional genesis, standards, campaign, resolution, priority, dependency, and parallel scheduling capabilities enabled.

## Delivery Slices

1. **Conversation ledger and projection**: durable conversations, messages, objective revisions, idempotent submission, replay, and typed APIs.
2. **Interpretation and context compiler**: strict speech acts, ambiguity handling, source-linked summaries, bounded context manifests, provider-neutral execution, and model provenance.
3. **Domain action adapters**: a closed capability registry and exact command correlations for genesis, work definition, staged planning, workflow start/cancel, repository analysis, coding progression, audit, promotion, standards, campaigns, and resolution without bypassing owning services.
4. **Multi-objective scheduler and recovery**: dependencies, priority, objective-scoped locking, worker selection beyond global mutexes, destination-repository serialization, pause/resume/cancel, asynchronous state projection, and crash reconciliation.
5. **Conversational desktop**: chronological transcript, objective rail, admissions, activity updates, diagnostics, and restart restoration.
6. **Orchard-on-Orchard proof**: use one conversation to deliver three consecutive Orchard changes through planning, coding, verification, independent audit, acceptance, and local promotion: one bounded defect, one cross-backend/frontend contract change, and one authority change requiring persistence compatibility, tests, and an ADR.

Each slice must leave a usable vertical path and executable tests. The ledger is implemented before model interpretation; interpretation before mutation adapters; one objective end to end before parallel scheduling; backend recovery before desktop polish.

## Acceptance Evidence

The milestone is complete only when tests and recorded demonstrations prove:

- duplicate message submission creates one message and at most one command;
- malformed, invented, stale, cross-project, and ambiguous references fail closed;
- discussion never mutates domain authority;
- model output alone cannot admit an objective or action;
- two independent objectives progress concurrently while commands within one objective remain ordered;
- no hidden conversation-wide or worker-wide mutex serializes independent eligible objectives, while same-destination promotion remains safely ordered;
- pause, resume, cancellation, dependency, and priority behavior survive restart;
- a crash between domain mutation and command correlation reconciles without duplicate work;
- every mutating capability persists or deterministically resolves its source command identity, and ambiguous adoption fails closed;
- a transcript exceeding the active model context remains usable through bounded reconstruction;
- switching from a cloud model to another compatible provider preserves conversation and objective continuity;
- one conversation can plan one objective, run another, observe an audit, and redirect a blocked objective without context leakage;
- every assistant claim about execution links to current durable authority or revision-pinned evidence;
- the desktop restores exact conversation chronology and objective states after backend and desktop restart; and
- the three defined Orchard-on-Orchard changes reach local promotion without repository source edits outside Orchard's admitted coding worktrees;
- conversation, explicit admissions, model/provider configuration, and inspection remain permitted during the proof; and
- every proof reports intervention count, model usage, elapsed time, failed attempts, final evidence, and whether any step bypassed the conductor.

## Consequences

- Orchard gains one durable conversational surface over its complete engineering lifecycle.
- Multiple objectives can be piloted without collapsing them into one prompt or one global lock.
- Existing authority investments become directly usable through conversation instead of separate operator screens.
- Cloud models can close reasoning-quality gaps without becoming persistence or mutation authority.
- Conversation history becomes inspectable and resumable across process restarts and provider changes.
- The new ledger and correlations add schema, replay, scheduling, and UI complexity.
- Natural-language ambiguity becomes an explicit product state rather than a reason to guess.
- End-to-end latency remains asynchronous; the conversation must communicate progress rather than pretending every request completes synchronously.

## Boundaries

- This decision does not create an infinite model context window.
- It does not allow unrestricted shell commands, direct store mutation, or model-authored admission.
- It does not replace existing domain services or duplicate their records into conversation authority.
- It does not introduce remote multi-client control, cryptographic identity, quorum, or signed decisions.
- It does not push Git remotes.
- Symbol-aware and semantic repository retrieval remain separately versioned intelligence improvements; the conductor initially uses existing revision-pinned repository analysis.
- Voice, mobile clients, distributed agent swarms, and autonomous budget increases are outside this milestone.

## Relationship to Prior Decisions

This proposal extends ADR 010 and ADR 011. Prompt-to-intent and deterministic validation remain valid, but the current direct request-to-workspace mutation path becomes only one typed adapter behind the conductor. It also relies on the authority separation established by ADR 038 through ADR 043.
