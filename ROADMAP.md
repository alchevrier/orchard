# Orchard Roadmap

## Roadmap Metadata

| Field | Value |
| --- | --- |
| Roadmap ID | `ORCHARD-ROADMAP` |
| Version | `7` |
| Status | `ACTIVE` |
| Updated | `2026-07-27` |
| Delivered baseline | Milestone 10.1 |
| Next milestone | `10.2.2` PR-Centered Multi-Actor Corrective Delivery |
| Canonical path | `ROADMAP.md` |

## How To Use This Roadmap

This file is Orchard's canonical statement of intended product direction. It is deliberately tracked at the repository root and treated as a foundation file by repository context collection so Architect, analysis, coding, audit, and conformance workflows can recover the plan in later sessions.

The roadmap is planning context, not execution authority. A milestone marked `NEXT` or `PLANNED` does not authorize repository mutation, bypass product genesis, admit a design, accept a plan, grant an exception, or change policy. Existing admitted runtime records and accepted ADRs remain authoritative for execution and architecture.

Authority precedence is:

1. Admitted runtime authority and revision-pinned evidence.
2. Accepted ADRs and non-bypassable integrity invariants.
3. This roadmap for sequence, intent, dependencies, and boundaries.
4. [User and developer documentation](docs/README.md) for current operation and implementation.
5. README summaries and conversational context.

Use these stable states:

- `COMPLETE`: delivered, validated, documented, committed, and included in the delivered baseline.
- `IN_PROGRESS`: implementation is admitted and underway, but one or more exit-evidence gates remain open.
- `NEXT`: the single preferred milestone to design and implement next.
- `PLANNED`: dependency-ordered work with an agreed problem and outcome, but not yet admitted for implementation.
- `CANDIDATE`: valuable direction that still needs architectural discovery or sequencing.
- `DEFERRED`: intentionally postponed with a stated reason.
- `SUPERSEDED`: replaced by another milestone or decision; retain the entry and link its replacement.

## North Star

Orchard is a local-first autonomous software company that can understand a repository, turn intent and policy into bounded delivery work, execute through evidence-producing controls, verify promoted outcomes, learn from failure, and preserve enough authority and provenance to explain every decision later.

The target product presents that company through three immediately useful surfaces: repository-first onboarding, an inbox-style interface for following and driving individual tickets, and a Jira-like project view for understanding the whole body of work. Architecture maps, UML and workflow projections, documentation and ADR conversations, correlation graphs, and generalized self-healing amplify these core surfaces after they are usable.

The product moat is not the number of agents or generated artifacts. It is the closed institutional loop:

```text
Intent and policy
  -> revision-pinned repository understanding
  -> admitted design and work
  -> bounded implementation
  -> verification and independent audit
  -> local promotion
  -> follow-up evidence
  -> closure, remediation, or governed escalation
```

### Immediate Product Priority

Orchard must first make the existing company understandable and operable without requiring the user to reconstruct state across setup forms, chat, logs, and isolated authority panels.

The priority order is:

1. **Repository-first onboarding**
  - Connect or clone the repository and open the project workspace immediately.
  - Derive a revision-pinned baseline of the repository's apparent intent, design, implementation, techniques, tests, reports, and evidence in the background.
  - Deliver the baseline progressively through report items instead of blocking entry behind a multi-step setup wizard.
  - Let the user confirm or correct inferred current intent and define the first desired outcome from the project workspace. Repository evidence cannot invent future human intent.
2. **Inbox-style project operation**
  - Make reports and ticket updates arrive as concise, threaded messages while Orchard works.
  - Render each report item's or ticket's one canonical conversation inside its Inbox content so the user can question, correct, prioritize, pause, approve, or request follow-up work without leaving the Inbox.
  - Make a user-started project conversation one new Inbox item backed by a durable report envelope and one canonical conversation.
  - Let users subscribe to meaningful changes for a ticket, outcome, capability, repository area, or later map selection without exposing a noisy internal event stream.
  - Bind every update to exact authority, repository revision, evidence, and downstream work so a reply has governed consequences rather than becoming detached chat history.
3. **Jira-like project overview**
  - Keep the project board as the portfolio-level view of outcomes, epics, stories, tasks, bugs, dependencies, state, and blocked decisions.
  - Make the board and inbox complementary projections of the same durable ticket authority. The board organizes all work; the inbox is where individual work is followed and driven.
  - Preserve direct navigation between the project overview and the canonical thread for any ticket or report item.

Later visual and autonomous capabilities must reuse this shared authority rather than create parallel truth. Maps explain how work and evidence connect; documents and ADRs explain why; self-healing repairs discrepancies without silently changing human intent.

## Non-Negotiable Invariants

Every roadmap milestone must preserve these constraints:

- Model output is candidate data until deterministic validation and the required admission authority accept it.
- Repository evidence, standards, prompts, plans, and decisions are pinned to immutable hashes or revisions.
- Coding cannot approve itself; verification, independent audit, acceptance, and promotion remain separate authorities.
- A completed task is not proof of repository-level outcome or conformance.
- Historical authority is append-only or revisioned; recovery never rewrites prior truth.
- Explicit exceptions narrow policy for a declared scope and duration; they never erase inherited policy silently.
- Local-first is the default. Remote inference and remote control require explicit policy and attributable authority.
- Orchard does not push a remote repository unless a future accepted decision introduces that authority.

## Delivery Sequence

### Milestone 10.1: Scoped Standards Overlays and Exception Authority

- State: `COMPLETE`
- Depends on: `9.8`, `9.9`, `10.0`
- Governing ADRs: ADR 040, ADR 041, ADR 042, ADR 043.

Goal: make effective engineering policy composable by scope and make `EXCEPTION_ACTIVE` a truthful, evidence-backed conformance disposition.

Why now: Milestone 10.0 can admit an `EXCEPTION_REQUEST`, and Milestone 9.8 recognizes active exceptions, but Orchard has no authority that can grant, scope, expire, revoke, or evaluate one.

Deliverables:

- Immutable organization, project, module, and work-item standard overlay records.
- A deterministic effective-standard resolver with explicit precedence and conflict reporting.
- Mandatory floors that child scopes may tighten but cannot weaken.
- Exception proposals and explicit admissions pinned to standard and repository revisions.
- Required exception scope, practice IDs, rationale, compensating controls, grantor, activation, expiry, review conditions, and revocation state.
- Routing from admitted Milestone 10.0 `EXCEPTION_REQUEST` decisions into candidate exception proposals without automatic grant.
- Conformance scans that emit `EXCEPTION_ACTIVE` only for an admitted, applicable, unexpired exception.
- Expiry, revocation, or scope drift that removes exception coverage and triggers new conformance attention.
- Cockpit projection of inherited policy, effective requirements, conflicts, exceptions, and expiry.

Exit evidence:

- Precedence and mandatory-floor tests cover every supported scope.
- Scope leakage, stale request, expiry, revocation, replay, and conflicting-overlay tests pass.
- A real scan proves that an applicable exception changes only its declared practices and scope.
- A follow-up scan proves that expired or revoked authority can no longer close a campaign.
- Full backend/frontend build, clean diagnostics, ADR, README update, and committed milestone.

Non-goals:

- Cryptographic identity, organizational login, quorum, and signatures.
- Fetching external policy repositories.
- Automatic legal or regulatory compliance claims.

### Milestone 10.2: Durable Multi-Objective Conversational Conductor

- State: `IN_PROGRESS`
- Depends on: `9.4`, `9.5`, `9.6`, `9.7`, `10.1`, `10.2.1`, `10.2.3`
- Governing ADRs: ADR 010, ADR 011, ADR 038 through ADR 047; ADR 044, ADR 045, and ADR 047 must be accepted before completion.

Goal: deliver the first complete Orchard operating experience: repository-first entry into a project, an inbox-style interface for following and driving ticket work, and a Jira-like project overview, all backed by the durable multi-objective conductor from discussion through local promotion.

Why now: Orchard already owns the individual company authorities and much of the durable conductor, but the user still encounters setup ceremony, conversational state, project state, and delivery evidence as separate experiences. The shortest path to immediate value is to enter the project from repository evidence, communicate meaningful progress through report and ticket threads, and retain the board as the whole-project control surface.

Product contract:

- One chronological conversation may contain several explicitly identified objective lanes.
- The operator can discuss, investigate, propose, admit, start, pause, resume, reprioritize, redirect, cancel, and inspect work from the inbox thread attached to the relevant ticket or report item.
- Each conversation is an independent durable Inbox item that may begin without project scope and later correlate with multiple projects, topics, tickets, reports, documents, or repository revisions.
- People may communicate chaotically across overlapping threads; Orchard compiles admitted consequences into ordered project-management, documentation, codebase, and governance projections without treating transcript text as authority.
- The project board shows all outcomes and tickets, while the inbox exposes subscribed changes, decisions, evidence, and conversations for individual work. Both project the same durable authority.
- Repository onboarding opens the project immediately and compiles its baseline asynchronously. Only future intent and genuine authority choices require user input.
- Conversation records prove what was said and correlated; existing domain records remain authoritative for product, work, code, evidence, audit, policy, and promotion.
- Model interpretation remains candidate data. Read-only discussion and inspection may run directly, while mutation requires an exact valid user admission.
- The user experiences durable report and ticket threads, but every model call receives a bounded reconstruction rather than an unbounded transcript.
- Independent objectives may progress concurrently under resource and repository policy; commands within one objective remain ordered.

Delivery slices:

1. **Conversation ledger and projection**
  - Checksummed append-only conversations, messages, immutable objective revisions, command proposals, admissions, and downstream correlations.
  - Stable IDs, content hashes, per-conversation sequence, client idempotency keys, recoverable replay, and typed APIs.
  - Objective states for candidate, awaiting admission, ready, active, paused, blocked, completed, cancelled, and superseded work.
2. **Interpretation and bounded context compiler**
  - A provider-neutral conversation profile with strict speech acts for discussion, inspection, objective formation, domain action, admission, control, status, and clarification.
  - Deterministic validation of every referenced conversation, message, objective, project, repository, proposal, and authority record.
  - Source-linked objective summaries, recent relevant dialogue, unresolved questions, current authority, and revision-pinned evidence compiled within the selected model budget.
  - Full model provenance, token usage, latency, prompt/output hashes, and provider-policy enforcement for every conversational inference.
3. **Typed domain-action adapters**
  - A closed capability registry declaring typed payload, read/mutation class, owning service, admission rule, allowed objective states, result type, and idempotency strategy.
  - Conductor adapters for genesis, work definition, staged planning, workflow start/cancel, repository analysis, coding progression, audit, promotion, standards, campaigns, and resolution.
  - Stable command IDs persisted as optional source references in downstream authority, or authority-specific deterministic adoption keys where extension is impossible.
  - Dispatch-intent records before service invocation and exact downstream ID/hash correlation afterward; recovery never matches by title, timestamp, or generated prose.
  - No direct store mutation and no duplicate conversational version of downstream authority.
  - Restart reconciliation when a domain mutation succeeds before conversational correlation is appended.
4. **Multi-objective scheduling and recovery**
  - Objective-scoped locks, acyclic dependencies, priority, resource admission, and independent-lane concurrency without a global chat mutex.
  - Run/case-scoped selection for analysis, coding, audit, and resolution workers where current service-wide mutexes prevent independent progress.
  - Destination-repository serialization and ancestry revalidation for promotion even when upstream worktrees execute concurrently.
  - Safe pause, resume, redirect, and cancellation semantics that preserve completed evidence and revalidate stale repository or policy authority.
  - Monotonic activity projection from asynchronous workers into the originating objective and conversation.
5. **Project inbox, ticket threads, and board**
  - An inbox-style desktop projection with unread, action-required, subscribed, blocked, and completed report updates while Orchard works asynchronously.
  - One canonical thread per independent conversation, with optional many-to-many correlation to tickets, reports, projects, documents, and repository evidence.
  - The canonical transcript, composer, objective controls, and command admissions embedded in the selected Inbox item, with board links selecting the same conversation there.
  - Every new conversation represented by one cross-project Inbox envelope and one canonical thread rather than a parallel global chat entry; later correlation never moves, merges, or replaces that thread.
  - User-created report scopes over a ticket, outcome, capability, or repository area, with subscriptions to meaningful revision-bound changes and configurable completion or continuation behavior.
  - A Jira-like project board for outcomes, epics, stories, tasks, bugs, dependencies, workflow state, and blocked authority, with direct navigation to each canonical thread.
  - Inline questioning, correction, control, review, and admission of exact proposed actions plus links into detailed authority and evidence projections.
  - Report updates are durable deltas over authoritative state, not rewritten summaries or a duplicate source of ticket truth.
  - Cursor-based refresh is sufficient initially; transport streaming is not required for correctness.
6. **Repository-first onboarding and model gate**
  - Accept either an absolute local Git folder or a credential-free HTTP(S) Git URL through one admitted `ONBOARD_REPOSITORY` command.
  - Clone URLs only into deterministic Orchard-managed storage with prompts, submodules, and LFS smudging disabled; never execute repository code during onboarding.
  - Create or select the project, bind the canonical repository, preserve exact command identity, and recover retries without duplicate projects or clones.
  - Open the project workspace after binding rather than requiring architecture, repository shape, sizing, or verification authority during onboarding.
  - Compile a revision-pinned baseline report of inferred current intent, design, code, techniques, tests, existing reports, and evidence as background work, with explicit supported, contradicted, unestablished, and stale findings.
  - Ask the user to confirm or correct inferred current intent and define the first desired outcome from the project workspace; defer unresolved implementation details into governed design and delivery.
  - Inspect installed model endpoints and bindings, register environment-referenced local or remote providers, and assign compatible bindings independently to conversation, definition, design synthesis, repository analysis, coding, and audit profiles.
  - Treat model installation or download as explicit machine setup; onboarding registers and verifies models but does not run package managers or `ollama pull`.
7. **Orchard-on-Orchard replacement proof**
  - Resume only after Milestone 10.2.1 replaces the falsified one-shot exact-replacement execution model.
  - Use one conversation to deliver three consecutive Orchard changes: a bounded defect, a cross-backend/frontend contract, and an authority change with persistence compatibility, tests, and an ADR.
  - Each change passes through design, an adequate executable work package, persistent coding and repair, verification, a candidate PR, independent audit, acceptance, and local promotion.
  - Conversation, explicit admission, provider configuration, and inspection are allowed; repository source edits outside Orchard's admitted coding worktrees are not.
  - Report human interventions, failed attempts, elapsed time, model/provider usage, tokens, peak memory, final promoted evidence, and any attempted bypass.

Exit evidence:

- Duplicate message delivery produces one durable message and at most one command or downstream action.
- Discussion cannot mutate authority, and model output cannot admit its own objective or action.
- Malformed, invented, ambiguous, stale, and cross-project references fail closed with a conversational clarification or diagnostic.
- Two independent objectives progress concurrently while one can be paused, redirected, or blocked without affecting the other.
- No hidden conversation-wide or worker-wide mutex serializes independent eligible objectives; same-destination promotion remains safely ordered.
- Objective dependencies remain acyclic and survive restart with exact state and priority.
- A crash between downstream mutation and correlation recovers without duplicate workspace entities, workflow runs, coding work, or promotion.
- Every mutating capability persists or deterministically resolves its source command identity; ambiguous adoption fails closed.
- A transcript larger than the selected model context remains operable through bounded, source-linked reconstruction.
- A compatible cloud-provider change preserves conversation, objective, and authority continuity.
- One conversation can plan one objective, execute another, observe an audit, and resolve a blocked objective without context leakage.
- Backend and desktop restart restore exact chronology, objective state, admissions, and correlated activity.
- Local-folder and URL onboarding produce one canonical bound project after retry or restart, reject credential-bearing URLs, and execute no repository-owned code.
- A newly bound project opens without a multi-step design wizard, produces a revision-pinned baseline report asynchronously, and lets the user define the first desired outcome from the project workspace.
- A user can create a report scope, subscribe to it, receive a meaningful evidence-backed delta, reply from its canonical thread, and observe the admitted consequence on the same ticket and project board after restart.
- The inbox and Jira-like board preserve one ticket identity and state; neither projection can drift from or silently overwrite the other.
- Workload-specific model assignments survive restart, reject incompatible context/capability budgets, and persist only credential references rather than secrets.
- After Milestone 10.2.1, the three defined Orchard-on-Orchard proof changes reach local promotion with no source edits outside Orchard worktrees and no bypass of the conductor or existing governance gates.
- Full backend/frontend build, clean diagnostics, compatibility tests, ADR acceptance, user/developer documentation, and committed milestone.

Non-goals:

- Treating the full transcript as one infinite model prompt.
- Letting chat records replace product, repository, evidence, audit, standards, or promotion authority.
- Unrestricted shell execution, silent mutation from ambiguous language, or model-authored admission.
- Remote multi-client control, cryptographic identity, quorum, signatures, or automatic Git push.
- Voice/mobile clients, distributed agent swarms, and automatic budget increases.
- Requiring the future symbol-aware evidence graph before the conductor can use existing revision-pinned repository analysis.
- Full architecture, UML, workflow, ADR, documentation, and evidence-map generation. These supercharge the core project experience after repository-first onboarding, inbox threads, subscriptions, and the board are proven.

Implementation evidence recorded on 2026-07-18:

- Durable ledger, strict bounded interpretation, typed APIs, exact admission/correlation/reconciliation, source-linked summaries, and the persistent desktop conductor are implemented.
- Analysis, coding, audit, and campaign-resolution work use run/case-scoped execution; coding claims and generated IDs are allocated atomically in durable stores.
- Objective state and dependencies are revalidated at dispatch, and paused/dependent correlated runs are filtered and priority-ordered before production worker scheduling.
- Conversational model provenance is structured and checksum-covered; capability descriptors expose ownership, admission, projected result, and idempotency contracts.
- Conversation, coding-worker, company-circuit, desktop-client, compatibility, and full Gradle build validation pass with no editor diagnostics.
- At that date, the remaining gate for the original conductor scope was to initialize admitted Orchard-on-Orchard authority and record the three required locally promoted changes, including intervention, provider/token, elapsed-time, failure, evidence, and bypass reports. ADR 044 remains `Proposed` until this proof succeeds.

Onboarding-gate evidence recorded on 2026-07-19:

- The conductor exposes admitted repository onboarding from absolute local folders or credential-free HTTP(S) Git URLs, with exact project command references and deterministic managed clone destinations.
- Managed cloning disables terminal prompts, submodule recursion, and LFS smudging and does not execute repository build or setup code.
- The conductor can inspect model configuration, register validated endpoint/binding pairs, and assign compatible bindings per execution profile without persisting credential values.
- Focused tests cover canonical local onboarding, restart idempotency, real HTTP Git cloning, embedded-credential rejection, model registration, compatible coding assignment, and incompatible-budget rejection.
- At that date, the remaining gate for the original onboarding scope was to run the three required Orchard-on-Orchard changes through the newly onboarded authority and record the proof report.
- These records prove the technical repository and model gate only. They do not claim completion of the Version 5 direct-to-project onboarding, baseline reports, subscriptions, canonical ticket threads, inbox, or board experience.

Execution-model experiment recorded on 2026-07-26:

- Orchard attempted a bounded two-file typography change through revision-pinned repository analysis, coding, verification, evidence, audit, acceptance, and promotion authority.
- Deterministic governance correctly rejected ambiguous or fabricated anchors, missing operations, cosmetic changes, tautological tests, partial semantic changes, and compile failures; no rejected candidate reached promotion.
- The 120B coding binding repeatedly failed to express the simple implementation through one-shot exact-text replacement proposals. Near-complete candidates were reverted and the repository was replanned instead of repairing local compile or test defects on the same candidate.
- This falsifies the current design assumption that additional context, exact replacement diagnostics, and bounded retries make one-shot model-authored patch serialization a viable general coding interface.
- The replacement proof is paused. Its durable attempts and retained candidates remain experiment evidence, and the backend dispatcher was stopped cleanly with the isolated worktree clean.
- Completion of Milestone 10.2 now depends on Milestone 10.2.1 and a successful replay using a materially smaller local coding model.

### Milestone 10.2.1: Executable Work Packages and Persistent Coding

- State: `DONE`
- Depends on: `9.4`, `9.5`, `9.6`, `9.7`, `10.1`
- Unblocks: `10.2`
- Governing ADR: ADR 046 (proposed until the replacement runtime and replay evidence are complete)

Goal: replace speculative one-shot patch generation with an engineering loop in which approved design becomes an adequate executable work package, a coding worker implements and repairs one persistent candidate, and independent analysis reviews the resulting PR against code, design, and intent.

Operating model:

- Principal authority owns need, intent, desired outcomes, and constraints.
- Senior design authority converts intent into a technical solution using the architecture bank and identifies ownership boundaries, risks, invariants, and verification strategy.
- Repository knowledge supports reuse and implementation discovery without pretending the design already knows every final changed file.
- A deterministic work-package compiler and independent adequacy verifier prove that a coding assignment is actionable before coding begins.
- The coding worker explores within admitted boundaries, proposes the actual file scope, writes code and tests, compiles, runs focused checks, and repairs the same isolated candidate until it passes or reports a genuine design blocker.
- A candidate PR freezes the actual diff, claims, tests, evidence, and deviations. Independent analysis then verifies that the claims match the code and that the implementation matches the admitted intent and design.

Deliverables:

1. **Versioned executable work package**
  - Pin intent revision, admitted design, repository revision, ownership boundary, allowed actions, constraints, invariants, expected behavior, verification strategy, and escalation conditions.
  - Include full editable source for bounded files or stable symbol-scoped source for larger files, plus relevant APIs, imports, repository conventions, and neighboring examples.
  - Distinguish likely design paths from the final implementation scope discovered by the coder.
2. **Independent package adequacy verification**
  - Reject missing behavior, contradictory constraints, unavailable APIs, insufficient source, unverifiable outcomes, and assignments that require the coder to make an architectural decision.
  - Answer whether a competent bounded coder can execute the package without rediscovering intent or design.
3. **Bounded coding tool protocol**
  - Replace model-authored exact old-text anchors with trusted operations such as bounded file rewrite, symbol replacement, class-member insertion, deterministic literal replacement with expected cardinality, file creation, deletion, source reads, and named checks.
  - Validate revision, path authority, syntax boundaries, operation cardinality, and resulting diff transactionally.
4. **Persistent candidate repair loop**
  - Preserve one isolated branch and candidate lineage across compile, test, lint, and semantic failures.
  - Return focused diagnostics and relevant source to the coding worker, checkpoint valid progress, and reserve repository reanalysis for stale authority or genuine design/scope defects.
  - Revert only when the package is abandoned, superseded, or proven invalid.
5. **PR-centered independent review**
  - Produce a local candidate PR artifact containing the actual diff, changed paths, implementation claims, tests, commands, evidence, and declared deviations.
  - Review claim truthfulness separately from intent and design alignment.
  - Preserve independent verification, architecture audit, quality audit, company acceptance, and controlled local promotion.
6. **Hardware and model degradation baseline**
  - Define supported 16, 32, 64, and 128 GiB profiles rather than treating a 64 GB model as the minimum viable coder.
  - Benchmark small, medium, and large coding bindings on the same fixed task suite and record completion, repair count, fabricated references, scope violations, changed lines, time, tokens, and peak memory.
  - Compare minimal tooling with the full Orchard architecture to measure what the system contributes beyond raw model capability.

Exit evidence:

- Replay the bounded typography task with no model-authored exact-text anchors and no repository reanalysis for local compile or test defects.
- A materially smaller local coding model changes exactly the required files, writes substantive production-bound tests, repairs at least one injected compile failure on the same candidate lineage, and reaches a reviewable PR.
- The coder may refine exact changed paths within the admitted ownership boundary; out-of-bound changes fail closed or escalate to design authority.
- Package adequacy tests reject missing source, unavailable imports or helpers, contradictory requirements, unverifiable outcomes, and hidden design decisions before coding starts.
- Deterministic tool tests cover stale revisions, ambiguous symbols, unexpected replacement cardinality, unauthorized paths, malformed source, partial application, restart recovery, and transactional rollback.

Implementation evidence recorded on 2026-07-26:

- Versioned executable work packages, deterministic adequacy admission, locked JSONL persistence, and package-pinned coding claims are implemented.
- Package-backed coding emits bounded tool batches rather than exact-text patch anchors. Trusted source reads, rewrites, creation, deletion, cardinality-checked literal replacement, and named checks fail closed at the package boundary.
- Verification and audit failures preserve and repair the current candidate lineage without repository reanalysis. The governed company scenario reaches audit, acceptance, and promotion through this persistent loop.
- Candidate PR artifacts freeze claims, paths, checks, evidence, and deviations; independent audit receives the PR with separate claim-truthfulness and intent/design review questions.
- The deterministic typography replay removes all six Serif uses present in the production source, injects a failing check, and repairs it on a direct descendant commit under one package.
- Durable benchmark records and the required small/large by minimal/full matrix are implemented for 16, 32, 64, and 128 GiB hardware profiles.
- The real `qwen3-coder:30b` replay completed on 2026-07-26 through Ollama 0.32.4's local llama.cpp-compatible runner endpoint. The model removed all six Serif references, added a production-source-bound test, received and repaired one injected unresolved-symbol failure, and produced passing frontend and full-build evidence on direct descendant commit `650215a4cb5033d95f76e1bff195a11af25590bb` of failed candidate `7fa08722b02d29e3d845478638d2ef0926e16f0e`.
- The candidate changed exactly the two admitted files, with 12 additions and 6 deletions. Accepted and corrective runner calls recorded 1,070 input tokens and 1,093 output tokens; the installed model artifact is 18 GB. Early malformed schema and source operations were rejected before mutation.
- Ollama 0.32.4's `/api/generate` and `/api/chat` proxy paths canceled this operation-shaped response after 72 generated tokens while the underlying local runner completed normally. The replay used Orchard's supported local compatible-provider boundary; provider conformance must retain a regression for this proxy behavior.
- The typography candidate was model-authored and validated through the bounded protocol in an isolated worktree, then promoted by the supervising developer. It does not claim that production Orchard performed package admission, audit, acceptance, and promotion for that run.
- PR analysis proves both that implementation claims match the actual code and that the result satisfies the pinned intent and design.
- Verification, independent audit, acceptance, and promotion remain separate from coding authority and reject self-approval.
- The benchmark reports quality degradation across model and hardware tiers and demonstrates measurable architectural compensation for smaller models.
- Full backend/frontend build, clean diagnostics, accepted ADR, updated user/developer documentation, and committed milestone.

Non-goals:

- Guaranteeing that every task succeeds on the smallest supported model.
- Allowing the coding worker to alter intent, weaken design constraints, approve its own package, or accept its own implementation.
- Unrestricted shell access or mutation outside admitted worktrees and ownership boundaries.
- Remote Git hosting integration; the first PR artifact and promotion remain local and revision-pinned.

### Milestone 10.2.2: PR-Centered Multi-Actor Corrective Delivery

- State: `NEXT`
- Depends on: `10.2.1`
- Unblocks: `10.2.3`
- Governing ADRs: ADR 004, ADR 036, ADR 046, and ADR 047.

Goal: make the local candidate PR the durable unit of implementation, independent review, correction, and downstream design reconciliation. Separate evidence, ownership, and operation authority before coding; preserve rejected candidates as immutable evidence; and compile focused successors from typed findings rather than repeatedly discarding useful work.

Deliverables:

1. **Compiled implementation authority**
  - Persist distinct revision-pinned evidence, ownership, and operation artifacts for every executable work package.
  - Compile package authority deterministically from admitted design, acceptance contract, repository evidence, ownership, and allowed actions.
  - Represent read-only compliant evidence explicitly so a cited source is never mistaken for a required code change or silently omitted from scope coverage.
2. **Candidate PR correction lineage**
  - Extend candidate PR records with parent lineage, terminal disposition, unresolved findings, and exact correction authority.
  - Keep rejected candidates immutable and inspectable; generate a bounded successor package for ordinary compile, test, verification, and review repairs.
  - Route stale authority, missing scope, and design contradiction to their owning authority instead of retrying coding under invalid premises.
3. **Separated review and design reconciliation**
  - Produce independent typed code-review, intent-review, design-review, and integration-review findings with evidence, requirement/invariant links, severity, and correction targets.
  - Build a revision-pinned dependency graph from design through package, candidate, finding, acceptance, and promotion.
  - Reconcile downstream candidates and packages after an admitted design successor without rewriting historical evidence or permitting obsolete promotion.
4. **Self-contained integration and actor conversation**
  - Start an isolated Orchard integration runtime with temporary storage, disposable repositories, ephemeral ports, deterministic time/model boundaries, real public APIs, restart exercise, and clean shutdown.
  - Project bounded specialist findings into the canonical conversation associated with the design, package, or candidate PR; conversation remains a projection over referenced authority.
  - Persist outcome-backed episodic learning and model/profile measurements without allowing conversation or model output to revise policy or acceptance rules.
5. **Resource-aware pipeline admission**
  - Schedule deterministic collection, compilation, and validation without model admission where possible.
  - Let independent work items pipeline on capable machines while constrained profiles serialize model stages and resume from the first invalid durable artifact.

Exit evidence:

- A candidate with an injected compile, test, code-review, and intent-review defect remains one inspectable lineage and receives bounded successors without broad repository reanalysis.
- A cited production source is deterministically classified as compliant read-only evidence or a required implementation owner before package admission.
- A design successor invalidates only dependent packages and candidates; unaffected candidates retain their promotion eligibility.
- A self-contained integration scenario starts a fresh Orchard runtime, uses public APIs and typed clients, survives a required restart, and shuts down with no user-specific external service prerequisite.
- Code, intent, design, and integration review findings remain distinguishable, evidence-linked, replay-safe, and cannot self-approve or promote a candidate.
- Full backend/frontend build, compatibility/replay coverage, ADR acceptance, developer and user documentation, and a committed milestone.

Non-goals:

- Replacing existing immutable evidence, audit, acceptance, or controlled promotion authority.
- Treating group conversation as a mutable authority graph or autonomous policy update mechanism.
- Requiring a live local model service for every integration test.

### Milestone 10.2.3: Orchard-Executed Governed Delivery

- State: `PLANNED`
- Depends on: `10.2.2`
- Unblocks: `10.2`
- Governing ADRs: ADR 036, ADR 046, and ADR 047.

Goal: execute one meaningful inbox-originated product change entirely through production Orchard, from admitted intent and design through package-backed coding, persistent repair, candidate PR review, independent audit, acceptance, and controlled local promotion.

Proof target: implement the existing Milestone 10.2 independent-conversation contract. Starting a conversation creates an unscoped cross-project Inbox item; admitted downstream work is aggregated into project-management, documentation, codebase, and governance views while the canonical conversation remains independent and immutable.

Exit evidence:

- A real project inbox work item starts the production analysis and package-backed coding path with a configured materially smaller local model.
- Multiple independent conversations can correlate with one authoritative work item, and one conversation can correlate with multiple domain records, without transcript merging or duplicated domain authority.
- Discussion alone changes no consolidated view; only an exact admitted action updates the owning domain and its projections.
- Orchard applies bounded operations only inside the admitted ownership boundary and retains one candidate lineage across an injected compile or test failure.
- Durable package, execution, candidate PR, evidence, audit, acceptance, and promotion records survive restart and are inspectable from the correlated inbox thread.
- Independent review separately proves candidate-PR claim truthfulness and intent/design alignment before acceptance.
- Accepted audit results invoke guarded local promotion without a manual promotion API call; stale, incomplete, or violating evidence fails closed.
- No supervising developer or external coding assistant authors, applies, repairs, approves, or promotes source during the proof run.

The small/large by minimal/full benchmark matrix continues as non-blocking measurement work. It informs supported hardware and model recommendations but does not block this MVP production proof.

### Milestone 10.2.4: Governed Autopilot and Outcome Experience Feedback

- State: `PLANNED`
- Depends on: `10.2.3`
- Governing ADRs: ADR 036, ADR 046, and ADR 047; a dedicated autopilot decision is required before admission.

Goal: let an operator explicitly delegate bounded delivery progression to Orchard while retaining evidence gates, stop conditions, and an auditable post-outcome feedback loop for work that was reported complete but proves incomplete in use.

Deliverables:

1. **Explicit autopilot policy**
  - Versioned project-scoped policy defining eligible work types, repository/module scope, risk ceiling, model/resource budgets, retry limits, required review/audit gates, notification level, and whether local promotion is delegated.
  - Default-deny policy: no remote push, exception grant, policy mutation, design admission, escalation resolution, or promotion outside the policy's exact delegated scope.
  - Deterministic stop conditions for design revision, clarification, escalation, audit violation, failed integration evidence, policy exception, stale authority, model/resource anomaly, or budget exhaustion.
2. **Evidence-gated autonomous progression**
  - Autopilot may advance only already-governed transitions whose exact evidence gates conform; it does not make model output authoritative.
  - Ticket reports and canonical conversations project the active policy, current stage, consumed budget, evidence, stop reason, and any automatic local promotion.
3. **Outcome experience reports**
  - Users can report `UNMET_CLAIM`, `REGRESSION`, `PARTIAL_OUTCOME`, `USABILITY_GAP`, `FALSE_POSITIVE_EVIDENCE`, or `NEW_REQUEST` against an accepted or promoted outcome.
  - Each report pins the candidate, work-package, design, review, audit, acceptance, and promotion authority available at submission, plus reproduction steps and optional evidence references.
  - Experience reports never rewrite historical acceptance; deterministic triage creates a corrective work item, review/integration attention, report-projection defect, or new outcome as appropriate.
4. **Retrieval-only learning and process measurement**
  - Derive recurring blind spots, verification gaps, reviewer/model-profile performance, and repository conventions from accepted outcomes and later experience evidence.
  - Inject bounded advisory learning into future design, package, review, and integration envelopes without allowing it to modify policy, design, acceptance, routing, or promotion.

Exit evidence:

- A policy outside its declared scope, risk ceiling, budget, or delegated transition fails closed and projects an actionable stop reason.
- An eligible low-risk ticket advances from admitted work through all required independent gates and local promotion without per-step user interaction.
- Clarification, escalation, design revision, failed audit, stale evidence, and resource/budget denial stop autopilot before the affected authority advances.
- A user experience report survives restart, preserves original decision history, creates the correct governed follow-up classification, and contributes only retrieval-only learning.
- The canonical ticket report explains both autonomous completion and any later unmet claim without presenting a revised narrative as historical truth.

Non-goals:

- Silent delegation inferred from conversational language.
- Automatic remote push, policy amendment, exception grant, escalation resolution, or broad model-budget increase.
- Treating user feedback or learned patterns as direct acceptance, design, or promotion authority.

### Milestone 10.3: Identity, Delegation, Quorum, and Signed Decisions

- State: `PLANNED`
- Depends on: `10.1`

Goal: replace caller-asserted actor strings with verifiable local authority for sensitive policy and acceptance decisions.

Deliverables:

- Durable human and service identities with local credential boundaries.
- Named roles, scoped delegation, expiry, and revocation.
- Policy-selectable quorum and segregation-of-duties rules.
- Signed admissions for standards, exceptions, promotions, and organizational policy changes.
- Historical verification that remains valid after role or delegation changes.
- Authentication and authorization on mutation APIs without exposing secrets to model context or persistence.

Exit evidence:

- Forged identity, expired delegation, insufficient quorum, self-approval, replay, and revoked signer tests fail closed.
- Existing local single-user projects have an explicit migration path.

Non-goals:

- General internet identity federation.
- Remote policy synchronization.

### Milestone 10.4: Verified Policy Sources and Deterministic Composition

- State: `PLANNED`
- Depends on: `10.1`, `10.3`

Goal: let organizations and communities publish reusable policy while preserving source identity, freshness, composition, and local inspectability.

Deliverables:

- Allowlisted Git policy sources with pinned revisions, manifests, signatures, and freshness limits.
- An open policy-pack format for standards, assurance packs, inspectors, and acceptance templates.
- Deterministic source synchronization and composition into effective scoped policy.
- Onboarding attestations for mandatory sources.
- Impact analysis when a source revision changes, with migration, re-attestation, remediation, or suspension decisions.
- Source-bound disposable RAG indexes that cannot become policy authority.

Exit evidence:

- Stale, missing, unsigned, conflicting, and unauthorized sources fail closed.
- In-flight work retains its pinned policy while new work adopts an admitted source revision.

Non-goals:

- Treating retrieval output as policy.
- Silent policy updates from arbitrary public repositories.

### Milestone 10.5: Resolution Action Executors and Policy Migration

- State: `PLANNED`
- Depends on: `10.1`, `10.4`

Goal: execute admitted non-delivery resolution decisions through specialized authorities instead of leaving them as durable requests.

Deliverables:

- `RESCAN` decisions schedule one revision-pinned conformance scan idempotently.
- `STANDARD_CLARIFICATION` decisions produce candidate successor standard revisions with impact analysis and explicit admission.
- Exception decisions link to the scoped exception authority from Milestone 10.1.
- Policy changes identify affected campaigns, work definitions, designs, and in-flight runs.
- Migration decisions preserve historical acceptance context and never retroactively authorize completed work.
- Revisioned supersession for previously admitted resolution decisions.

Exit evidence:

- Duplicate scheduling, stale decisions, policy drift, supersession, and restart recovery tests pass.
- No non-delivery resolution action can mutate its target authority directly.

## Product Evolution Arc

### Milestone 11.0: Conversational Product Successor Revisions

- State: `PLANNED`
- Depends on: `10.5`

Goal: let an admitted product evolve through a conversational successor revision with deterministic downstream invalidation.

Deliverables:

- Candidate genesis successor revisions pinned to the current admitted revision.
- Impact analysis across experience, architecture, blueprint, designs, plans, campaigns, and repository state.
- Explicit preserve, supersede, migrate, cancel, or re-attest decisions for affected authority.
- No silent mutation of in-flight work.

### Milestone 11.1: Repository ADR Export and Correlation

- State: `PLANNED`
- Depends on: `11.0`

Goal: export admitted architectural decisions into repository Markdown while retaining Orchard identity and correlation.

Deliverables:

- Deterministic ADR rendering with stable Orchard authority IDs and hashes.
- Safe updates through successor ADRs rather than rewriting historical decisions.
- Bidirectional correlation among repository ADRs, designs, implementation evidence, and conformance findings.

### Milestone 11.2: Portfolio and Roadmap Governance

- State: `CANDIDATE`
- Depends on: `11.0`

Goal: evolve this repository roadmap from retrieval context into admitted portfolio authority with priorities, capacity, dependencies, and outcome review.

Candidate scope:

- Parse a versioned roadmap schema into candidate portfolio records.
- Require explicit admission before roadmap items create product or delivery work.
- Record milestone forecasts versus actual evidence without treating estimates as guarantees.
- Update roadmap status from promoted evidence rather than model assertion.

## Repository Intelligence Arc

### Milestone 12.0: Symbol-Aware Repository Evidence Graph

- State: `IN_PROGRESS`
- Depends on: `11.1`

Goal: replace bounded lexical file selection as the primary navigation mechanism with source-aware, revision-pinned evidence relationships.

Deliverables:

- Complete content-addressed import of every Git-tracked artifact with explicit coverage and a durable project graph projection. **Implemented foundation.**
- Correlation of repository modules, declarations, imports, tests, ADR path references, build dependencies, and current Orchard project/work/design/workflow/evidence authority. **Implemented deterministic foundation.**
- Typed diagnoses for partial, conflicting, and missing repository evidence, with revision-pinned governed remediation prompts for architecture, decisions, tests and test methodology, and delivery evidence. **Implemented baseline workflow.**
- Language-server or parser-backed symbols, definitions, references, modules, tests, and configuration relationships.
- Content-addressed evidence nodes tied to exact repository revisions.
- Deterministic fallback for unsupported languages.
- Retrieval explanations showing why every file or symbol entered model context.

### Milestone 12.1: Source-Bound Semantic Retrieval

- State: `PLANNED`
- Depends on: `12.0`

Goal: add semantic recall without allowing embeddings or generated summaries to replace source authority.

Deliverables:

- Disposable vector indexes keyed by repository revision and source content hash.
- Hybrid lexical, symbol, and semantic ranking.
- Citation verification against original tracked files before model use or admission.
- Index invalidation and rebuild after repository or policy revision changes.

### Milestone 12.2: Evidence-Derived Organizational Learning

- State: `CANDIDATE`
- Depends on: `12.1`, `10.4`

Goal: derive reusable practices from accepted outcomes while keeping learned guidance separate from policy until admitted.

Candidate scope:

- Distill recurring accepted patterns, failures, and review corrections into candidate practices.
- Track provenance from every candidate lesson to completed episodes and evidence.
- Require explicit standards or policy-pack admission before learned guidance becomes enforceable.

### Milestone 12.3: Live Correlated Product Map and Conversational Knowledge

- State: `PLANNED`
- Depends on: `11.1`, `12.1`

Goal: generate live architecture, UML, workflow, documentation, ADR, ticket, and evidence projections over one revision-bound correlation model, and let the user converse from any projected object.

Deliverables:

- A shared artifact and derivation graph spanning intent, design authority, work, code, configuration, techniques, tests, reports, and runtime evidence.
- Jira-like work, architecture, component, sequence, workflow, ADR-impact, and intent-to-evidence views generated as queries over shared authority rather than independent documents.
- Stable links from every projected node and relationship to repository evidence, authority revisions, tickets, reports, and canonical conversation threads.
- User-created report scopes from selected map paths with subscriptions that emit meaningful revision-bound deltas into the project inbox.
- Conversational successor proposals for documentation and ADRs with explicit impact analysis and admission before authority changes.
- Deterministic invalidation of affected projections and evidence after an admitted intent, ADR, design, code, test, or repository revision changes.

Exit evidence:

- One admitted ADR revision identifies affected design, tickets, code, tests, reports, and map relationships without rewriting prior history.
- One report subscription follows a selected end-to-end capability path and emits only relevant changes when its evidence or integration state changes.
- Board, inbox, document, architecture, and evidence projections resolve to the same underlying authority IDs and repository revisions after restart.

### Milestone 12.4: Generalized Correlation Repair and Self-Healing

- State: `CANDIDATE`
- Depends on: `9.9`, `12.3`

Goal: detect and repair missing, stale, contradictory, or disconnected derivation relationships across the product while preserving human intent and admission boundaries.

Candidate scope:

- Classify missing artifacts, missing derivations, inadequate coverage, contradictions, disconnected implementations, stale evidence, and unproven reports.
- Find the earliest invalid point in a derivation chain, invalidate dependent projections, and select the narrowest governed repair technique.
- Re-run downstream implementation, verification, audit, reporting, and map compilation after repair.
- Escalate only when a repair would change human intent, policy, architecture authority, or another explicitly protected decision.
- Publish repair progress and evidence through the affected ticket threads and report subscriptions.

## Operational Scale Arc

### Milestone 13.0: Storage Lifecycle and Workspace Capacity

- State: `PLANNED`
- Depends on: `11.0`

Goal: remove the current 32-entity product limit without weakening replay, inspectability, or recovery.

Deliverables:

- Governed archival and retention for worktrees, branches, evidence, model executions, and completed workspace entities.
- Capacity-safe pagination or partitioning of active workspace authority.
- Rehydration and audit access to archived records.
- Storage budgets and deterministic cleanup policy.

### Milestone 13.1: Cost, Latency, and Resource Accountability

- State: `PLANNED`
- Depends on: `13.0`

Goal: make campaign and company resource consumption first-class evidence for planning and governance.

Deliverables:

- Per-model, per-role, per-campaign token, latency, memory, and optional monetary accounting.
- User-delegated budgets and fail-closed admission when exhausted.
- Outcome-linked efficiency metrics that do not reward bypassing quality gates.

### Milestone 13.2: Self-Hosted Organizational Control Plane and Remote Runners

- State: `CANDIDATE`
- Depends on: `10.2`, `10.3`, `13.0`, `13.1`

Goal: let an organization host Orchard on its own servers as the shared engineering control plane through which authenticated teams pilot projects, objectives, models, policy, evidence, audit, acceptance, and promotion without surrendering repository or model custody.

Candidate scope:

- A self-hosted organization deployment with durable conversation, objective, policy, admission, evidence-correlation, audit, and acceptance authority shared across teams and repositories.
- Organization, team, project, and repository tenancy derived from Milestone 10.3 identities, roles, scoped delegation, quorum, revocation, and signed decisions.
- Authenticated web, desktop, API, and automation clients with optimistic concurrency, leases, conflict projection, monotonic event cursors, and complete actor attribution.
- Organization-managed runners that connect outbound to the control plane, advertise repository, toolchain, model, and resource capabilities, and execute leased work near private source and local inference.
- Signed runner evidence, heartbeats, cancellation, bounded retry, reconnect adoption, and duplicate-safe completion when either runner or control plane restarts.
- Explicit per-project custody policy selecting local runner, organization-hosted runner, or admitted managed execution for repository access, model inference, artifacts, and optional remote Git publication.
- Short-lived repository and service credentials resolved only at the execution boundary; secret values never enter conversation context, model prompts, authority records, or hosted logs.
- Tenant-isolated transactional authority storage and immutable artifact retention that preserve Orchard's append-only semantics while supporting backup, disaster recovery, audit, and governed lifecycle policy.
- Complete export of conversations, authority records, policy, evidence manifests, and cryptographic verification material so an organization can migrate or return to standalone operation without losing history.
- Deployment and upgrade procedures for one organization to operate Orchard on its own infrastructure without requiring an Orchard-operated cloud service.

Exit evidence:

- Two authenticated users from separate teams can conduct independent objectives concurrently while unauthorized cross-team reads, admissions, mutations, and evidence access fail closed.
- An organization-managed runner completes one repository workflow through analysis, coding, verification, independent audit, acceptance, and promotion without exposing source or secret values to the control plane.
- Runner disconnect, duplicate delivery, lease expiry, control-plane restart, and stale completion recover without duplicate repository mutation or authority records.
- Organization policy and quorum requirements are enforced identically through web, desktop, API, and automation clients.
- A complete authority export can be verified and restored into a clean self-hosted deployment with chronology, signatures, correlations, and terminal project state intact.

Non-goals:

- Requiring a public Orchard SaaS or Orchard-operated infrastructure.
- General internet identity federation beyond explicitly admitted organization providers.
- Silent remote Git publication, unrestricted inbound access to runner networks, or transfer of private source into model context without explicit custody policy.
- Distributed agent swarms whose coordination is not justified by admitted objectives and resource accountability.

## Deferred Directions

These directions are intentionally not scheduled:

- Automatic remote Git push: deferred until identity, review, and remote publication authority exist.
- Autonomous legal or regulatory compliance claims: Orchard may enforce technical controls but cannot manufacture legal authority.
- Silent model self-training from private repositories: deferred until provenance, consent, evaluation, rollback, and model-governance boundaries are designed.
- Unbounded autonomous retries: rejected in favor of evidence-backed resolution and explicit resource policy.
- Distributed agent swarms: deferred until repository understanding, authority composition, and operational accountability justify the coordination cost.

## Completed Baseline

The README contains the detailed delivered history. The current architectural line is:

| Milestone | Outcome | State |
| --- | --- | --- |
| 9.4 | Guided Product Genesis | `COMPLETE` |
| 9.5 | Local Autonomous Company | `COMPLETE` |
| 9.6 | Repository Analysis and Execution-Plan Compilation | `COMPLETE` |
| 9.7 | Provider-Neutral Model Runtime | `COMPLETE` |
| 9.8 | Engineering Standards and Conformance Compiler | `COMPLETE` |
| 9.9 | Closed-Loop Conformance Remediation | `COMPLETE` |
| 10.0 | Campaign Resolution and Successor Governance | `COMPLETE` |
| 10.1 | Scoped Standards Overlays and Exception Authority | `COMPLETE` |

Earlier milestones established the workspace, deterministic workflows, durable evidence, work definitions, staged circuits, requirement authority, contract-compiled gates, governed coding, toolchain policy, and product/company foundations required by this line.

## Roadmap Update Protocol

Update this file in the same change that alters roadmap intent.

1. Keep exactly one milestone in state `NEXT`.
2. Update `Version`, `Updated`, `Delivered baseline`, and `Next milestone` when the delivered baseline changes.
3. Mark a milestone `COMPLETE` only after implementation, focused tests, full build, documentation, and commit are complete.
4. Add or update an ADR when sequencing introduces a new authority boundary or changes an accepted architectural decision.
5. Move displaced work to `DEFERRED` or `SUPERSEDED`; do not delete it and erase the decision trail.
6. Keep milestone IDs stable. If scope changes materially, explain the change in the roadmap log.
7. Keep README project status concise and link here for future direction.
8. Treat Git history as the immutable record of prior roadmap versions; the log below summarizes meaningful planning changes.

## Roadmap Log

| Date | Version | Change |
| --- | --- | --- |
| 2026-07-21 | 5 | Reprioritized Milestone 10.2 around repository-first onboarding, an inbox-style report and ticket interface, and the Jira-like project overview; sequenced live visual correlation, conversational ADRs and documentation, and generalized self-healing as later product multipliers. |
| 2026-07-19 | 4 | Expanded Milestone 13.2 from remote Architect access into a self-hosted organizational control plane with authenticated multi-client use, organization-managed runners, source custody, recovery, and authority export. |
| 2026-07-18 | 3 | Prioritized a durable multi-objective conversational conductor as Milestone 10.2 and moved identity and later policy work behind the workflow-replacement proof. |
| 2026-07-18 | 2 | Completed scoped standards overlays and exception authority; selected identity, delegation, quorum, and signed decisions as Milestone 10.2. |
| 2026-07-18 | 1 | Established the canonical roadmap after Milestone 10.0; selected scoped standards overlays and exception authority as Milestone 10.1. |
