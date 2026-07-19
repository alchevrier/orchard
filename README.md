# Orchard

**Plant intent, harvest software.**

Orchard is a cloneable, runnable work-in-progress prototype for turning natural-language intent into governed, evidence-producing software workflows. Its local-first stack combines a durable conversational conductor, a Compose Desktop control surface, a Ktor backend, deterministic workflow validation, repository execution, and configurable local or explicitly permitted remote inference.

> **Project status:** Milestone 10.2 is in progress. The durable multi-objective conductor, repository onboarding, workload-specific model onboarding, and hardware-aware local model presets are implemented and validated. Completion remains intentionally blocked on the three-change Orchard-on-Orchard replacement proof.

> **Roadmap:** See [ROADMAP.md](ROADMAP.md) for the canonical dependency-ordered plan, milestone states, exit evidence, and update protocol. The active milestone is 10.2: Durable Multi-Objective Conversational Conductor.

## Documentation

- [User Guide](docs/user-guide/README.md): install, configure, operate, govern, and recover Orchard.
- [Developer Documentation](docs/developer/README.md): architecture, development, persistence, APIs, and extension points.
- [Architecture Decision Records](docs/adrs): accepted decisions and their consequences.
- [Documentation Portal](docs/README.md): documentation authority and maintenance rules.
- [Roadmap](ROADMAP.md): dependency-ordered product intent and exit evidence.

## License

Orchard is licensed under the [Apache License 2.0](LICENSE).

## Milestone 10.2: Durable Multi-Objective Conversational Conductor (`IN_PROGRESS`)

Orchard now exposes one restart-safe conversation as the primary control surface for several governed engineering objectives. Conversation records prove what was proposed, admitted, dispatched, and correlated; existing product, workflow, repository, evidence, audit, standards, and promotion records remain the owning authorities.

Implemented and validated:

- Checksummed append-only conversations, messages, objective revisions, exact command proposals, admissions, downstream correlations, idempotency keys, and restart recovery.
- Bounded conversation reconstruction with source-linked summaries, strict typed interpretation, model provenance, token and latency evidence, and no unbounded transcript replay.
- Objective-scoped state, dependencies, priority, pause, resume, redirect, cancellation, and concurrent scheduling without a conversation-wide worker lock.
- A closed capability registry for project genesis, work definition, staged planning, workflow control, company start, local promotion, repository inspection, and status.
- Exact mutation admission: model output remains candidate data, and only the admitted command ID and content hash may invoke an owning domain service.
- Durable dispatch intent and exact downstream correlation with restart reconciliation when a domain mutation succeeds before conversation correlation is appended.
- Repository onboarding from an absolute local Git folder or credential-free HTTP(S) URL into deterministic Orchard-managed storage without executing repository code.
- Model configuration inspection, endpoint and binding registration, and independent assignment across conversation, definition, circuit synthesis, repository analysis, coding, and audit workloads.
- Hardware-aware Ollama presets for classic PCs and Apple silicon from 8 GiB through 128 GiB, with complete stage routing and admission-safe context apertures.
- A restored Compose Desktop transcript, objective rail, pending admissions, activity projection, diagnostics, and links into detailed authority views.

Completion gate:

- Orchard must use the conductor to deliver three consecutive changes to Orchard itself: a bounded defect, a backend/frontend contract change, and a persistence-compatible authority change.
- Each change must reach local promotion through planning, coding, verification, independent audit, and acceptance with no source edits outside admitted Orchard worktrees.
- The proof must report human interventions, failed attempts, elapsed time, provider and token usage, final evidence, and any attempted bypass. Until then, ADR 044 remains `Proposed` and Milestone 10.2 remains `IN_PROGRESS`.

See [Durable Conversations](docs/user-guide/conversations.md) for operation and [ADR 044](docs/adrs/044-durable-multi-objective-conversational-conductor.md) for the proposed architectural decision.

## Delivered Milestone History

The sections below record what each milestone delivered and the boundaries that applied at that point in Orchard's development. For current operation and limits, use the project status above and the [User Guide](docs/user-guide/README.md).

## Milestone 10.1: Scoped Standards Overlays and Exception Authority

Engineering policy now composes deterministically instead of relying on a single project-wide document or informal team memory. Exceptions are separate, explicit authority: a request remains a candidate until admitted, and continued effect depends on scope, policy, Git ancestry, time, revocation, and unchanged compensating-control evidence.

Delivered and verified:

- Immutable organization, project, module, and work-item overlay revisions in a checksummed append-only policy ledger.
- Deterministic scope precedence, nested module specificity, cumulative same-scope revisions, and explicit composition conflicts.
- Add, tighten, and disable operations with mandatory floors that narrower scopes cannot weaken.
- Hash-pinned effective standards persisted with conformance scans for historical explanation and replay.
- Evidence-bound exception proposals with explicit admissions, bounded activation and expiry, revocation, supersession, and invalidation states.
- Deterministic `EXCEPTION_ACTIVE` validation restricted to applicable admitted practices while still permitting independently conforming evidence.
- Policy-aware scan and campaign identities, allowing reevaluation at unchanged Git HEAD after exception admission, expiry, revocation, or evidence drift.
- Idempotent conversion of admitted campaign `EXCEPTION_REQUEST` decisions into candidate exception proposals without automatic grant.
- Typed backend APIs, desktop client contracts, and cockpit projection for effective practices, floors, conflicts, exception state, expiry, and resolution lineage.
- Backward-compatible standards and campaign serialization with legacy canonical hashes unchanged when new authority fields are absent.

Milestone 10.1 boundaries:

- Actor and grantor values remain attributable strings, not authenticated identities; roles, delegation, quorum, and signatures are Milestone 10.3.
- Organization overlays are local authority, not verified external policy sources.
- Compensating-control evidence proves unchanged repository bytes, not real-world process effectiveness.
- The cockpit focuses authoring on project policy; typed APIs expose all supported scopes.
- Historical scans and campaign evaluations remain immutable when policy authority later changes.

## Milestone 10.0: Campaign Resolution and Successor Governance

Terminal campaigns are now governed decision points rather than dead ends. Orchard distinguishes exhausted remediation, conflicting or unknown evidence, and regressions; asks the Architect for one bounded candidate response; and preserves the human admission boundary before reopening delivery authority.

Delivered and verified:

- Checksummed append-only resolution cases, proposals, and admissions with restart replay and one admitted decision per terminal evaluation.
- Automatic case reconciliation for `BLOCKED` and `ESCALATED` campaigns, pinned to exact evaluation hashes and promoted repository revisions.
- Deterministic causes for remediation exhaustion, evidence conflict, evidence uncertainty, and regression.
- Provider-neutral broad-analysis Architect proposals with strict JSON, resource admission, content-hashed model provenance, and a closed action vocabulary.
- Candidate actions for additional remediation, investigation, rescan, exception request, standard clarification, and abandonment.
- Structural validation that delivery proposals cover selected practices through one valid Epic/Story/leaf hierarchy and use only admitted verification commands.
- Explicit admission with stale-evaluation and clean-repository revision checks before any workspace mutation.
- Atomic workspace creation and exact proposal-node/entity mappings for admitted remediation and investigation decisions.
- New successor campaigns pinned to predecessor campaign, resolution case, proposal, admission, terminal scan, and created entities.
- Idempotent recovery when workspace creation succeeds but resolution persistence is interrupted.
- Durable non-delivery decisions that create no repository entities and cannot impersonate exception, standard, or scan authority.
- Typed APIs and cockpit controls for resolution generation, rationale review, explicit admission, terminal causes, and successor lineage.
- Backward-compatible Milestone 9.9 campaign serialization with unchanged legacy hashes and checksums.

Milestone 10.0 boundaries:

- Rescan admission records the decision but does not yet schedule the scan automatically.
- Exception requests and standard clarifications do not grant exceptions or mutate standards; those require dedicated policy authorities.
- One admitted decision resolves a case; revisioned supersession is future work.
- Authentication, organizational roles, quorum, signatures, and organization/module policy overlays remain future milestones.
- Successor delivery remains bounded by the existing 32-entity workspace capacity.

## Milestone 9.9: Closed-Loop Conformance Remediation

Admitted conformance findings now become durable remediation campaigns rather than disconnected backlog items. A campaign coordinates Orchard's existing company runtime and proves closure from a promoted repository revision; finishing a Task alone can never close a finding.

Delivered and verified:

- Checksummed append-only campaign and evaluation authority with restart replay and one campaign per admitted conformance scan.
- Exact persisted mappings from candidate backlog node IDs to admitted workspace entity IDs.
- Stable cross-scan practice identity independent from model-generated finding IDs.
- Deterministic compilation of candidate leaves into READY work definitions, inherited design contracts, and covering staged delivery circuits.
- Sequential campaign slicing that prevents a second leaf from compiling until every prior linked run has an accepted local promotion.
- Reuse of broad repository analysis, bounded coding, verification evidence, dual independent audit, company acceptance, and local fast-forward promotion.
- Automatic follow-up scans pinned to the campaign's immutable standard revision and exact promoted repository revision.
- Idempotent campaign evaluations keyed by campaign and repository revision.
- Evidence-derived `CLOSED`, `IN_PROGRESS`, `BLOCKED`, and `ESCALATED` outcomes with regression detection across all seeded practices.
- Immediate post-admission reconciliation plus periodic recovery ticks after restart or missed triggers.
- Typed campaign APIs and cockpit projection of linked work, promoted revisions, practice transitions, closure, blocking, and regressions.
- Focused tests for replay, duplicate rejection, campaign compilation, product-genesis interlocks, terminal outcomes, and unchanged-revision idempotency.

Milestone 9.9 boundaries:

- Campaign creation still requires explicit conformance backlog admission.
- Product genesis, work definition, design, verification, audit, and promotion gates remain mandatory.
- Blocked or escalated campaigns require a later human or Architect decision; automatic successor remediation is deferred.
- Organization/module standards overlays, first-class campaign cost accounting, and workspace-capacity expansion remain future work.
- Orchard never pushes a remote; campaign promotion is local and revision-pinned.

## Milestone 9.8: Engineering Standards Authority and Repository Conformance Compiler

Orchard now has an explicit, versioned answer to what good engineering means for a project. Standards are independent from architectural decisions and repository implementation, allowing the local company to identify drift without converting current defects into policy.

Delivered and verified:

- A built-in adjustable baseline for durable authority, model-output admission, evidence-bound delivery, secret custody, and ADR-to-implementation correlation.
- Immutable project standard revisions with stable practice IDs, required/advisory severity, applicability, evidence requirements, remediation, enablement, and hash chaining.
- Full clean-HEAD repository scans through the 88K broad analysis profile, with exact content-hash citations across ADRs, source, tests, and configuration.
- Seven explicit dispositions: conforming, nonconforming, partial, not applicable, unknown, conflicting, and active exception.
- Deterministic rejection of invented practices, evidence, paths, commands, incomplete required remediation, omitted actionable findings, invalid hierarchy, oversized proposals, and repository drift.
- Semantic candidate compilation into one `Epic -> Story -> Task/Bug/Investigation` hierarchy without mutating workspace authority.
- Explicit atomic backlog admission with revision revalidation, capacity preflight, duplicate rejection, and retry-safe admission journaling.
- Checksummed append-only persistence for standards, scans, and admission events with full standard/repository/model/prompt/context/output provenance.
- Typed Ktor APIs and a cockpit authority panel for editing standards, running scans, reviewing findings and citations, inspecting candidate work, and admitting it.
- Real-Git tests proving scans are non-mutating, citations are revision-bound, invented evidence is rejected, and admission creates the exact hierarchy once.

Milestone 9.8 boundaries:

- The first authority layer is project-scoped; organization, module, and explicit scoped-exception overlays remain future work.
- Evidence selection is bounded tracked-file retrieval rather than complete language-server or vector-symbol indexing.
- Conformance scans propose admitted build-system commands but do not execute verification during classification.
- Candidate admission remains subject to the current 32-entity workspace capacity.
- Orchard can self-scan by binding an Orchard project to this checkout, adopting the baseline, and running the same operator flow used for any repository.

## Milestone 9.7: Provider-Neutral Model Runtime

Model execution is now deployment-independent while remaining local-first. A durable catalog maps role-compatible model bindings to endpoint adapters, and the running company sees catalog replacement without a restart.

Delivered and verified:

- Native Ollama and OpenAI-compatible REST adapters with strict JSON requests, bounded responses, token accounting, timeouts, and redirect refusal.
- Checksummed atomic provider configuration covering endpoints, bindings, context capacity, resource demand, and non-secret generation settings.
- Server-enforced `LOCAL_ONLY`, `LOCAL_PREFERRED`, `CLOUD_ALLOWED`, and `CLOUD_ESCALATION_ONLY` policies.
- Request-time bearer resolution from `env:NAME` references; API key values never enter Orchard persistence or provenance.
- Live registry wiring for Architect, Genesis, work definition, circuit synthesis, repository analysis, coding, and company staffing.
- Catalog retrieval/replacement and explicit model-discovery health APIs.
- Hardware-aware classic PC and Apple silicon presets covering 8, 16, 32, 64, 96, and 128 GiB tiers with complete workload-to-model routing and admission-safe apertures.
- First-run recommendation and one-shot application in the cockpit, followed by independent customization of every workload stage.
- Setup and launch scripts that install and verify the complete recommended role-model set while preserving explicit model overrides.
- Cockpit configuration for Ollama, LM Studio, other OpenAI-compatible servers, and optional remote endpoints.
- Deterministic tests for persistence recovery, secret rejection, remote-policy rejection, both transport protocols, discovery, bearer resolution, live API replacement, and local-preferred routing.

Milestone 9.7 boundaries:

- The guided preset flow configures multiple role bindings; detailed provider editing still operates on one active endpoint and binding at a time.
- Cloud escalation is capability/context fallback, not automatic retry after a local generation failure.
- Streaming, tool calling, multimodal requests, pricing, rate-limit scheduling, OAuth, and secret-manager integrations remain future adapters.
- Default onboarding uses Ollama at `127.0.0.1:11434` with machine-sized role models; LM Studio uses its OpenAI-compatible base URL, normally `127.0.0.1:1234`.

## Milestone 9.6: Repository Analysis and Execution-Plan Compilation

The local company now performs the architectural heavy lifting before coding. A staffed analyst-designer inspects revision-pinned repository evidence, distinguishes scaffolding from real behavior, finds reusable or partial implementations, and compiles an immutable execution plan. The coding worker receives only that accepted plan and its target files.

Delivered and verified:

- A dedicated `broad-repository-analysis-v1` profile with an 88K input aperture and independently staffed `ANALYST_DESIGNER` role.
- Seven implementation dispositions: absent, scaffold-only, partial, different-form, nonconforming, complete, and conflicting.
- Content-hashed repository citations proving every admitted analysis observation came from supplied revision-bound bytes.
- Immutable, checksummed `repository-analysis-plans.jsonl` authority with monotonic successor plans after repository drift.
- Coder-ready operations naming exact action, path, optional symbol, instruction, acceptance criteria, invariants, reuse targets, non-goals, and admitted verification commands.
- Deterministic admission that rejects invented evidence, mismatched repository shape, uncovered acceptance criteria, changed verification commands, and unresolved architecture.
- A reduced 24K coding profile whose repository context contains only accepted plan targets.
- Coding claims pinned to exact execution-plan identity and hash, with unauthorized paths or action classes rejected before mutation.
- `ANALYSIS_REQUIRED`, `PLAN_STALE`, and `PLAN_BLOCKED` outcomes that route uncertainty to analysis instead of coder improvisation.
- Cockpit projection of implementation disposition, reuse strategy, ordered operations, and the broad-analysis/bounded-coding split.
- End-to-end proof of scaffold detection, in-place reuse, unauthorized scope rejection without mutation, stale-plan reanalysis, coding repair, independent audit, acceptance, and local promotion.

Milestone 9.6 boundaries:

- Repository selection is bounded lexical retrieval over tracked text files; language-server symbols and source-bound vector retrieval remain future precision improvements.
- The model proposes analysis and design, but deterministic validation is admission authority.
- Conflicting implementations and unresolved architectural questions require the local architect; they are never silently delegated to coding.
- A `COMPLETE` disposition currently blocks coding for explicit verification handling rather than automatically closing the workflow.

## Milestone 9.5: Local Autonomous Company

An admitted genesis can now start a restart-safe company circuit. Orchard materializes greenfield repositories, compiles architecture into auditable rules, assigns local models as evidence-ranked staff, executes implementation work, and requires independent architecture and quality judgments before local promotion.

Delivered and verified:

- Continuous company phase projection over the existing staged-plan, dispatch, workflow, worker, evidence, and Git authorities.
- A local staff registry with role, capability, risk, model-binding, assignment, escalation, and observed-experience provenance.
- Deterministic compilation of admitted product architecture into revision-bound audit rules.
- Governed coding execution pinned to the assigned staff identity and model binding.
- Independent read-only architecture and quality audits whose findings cite current candidate evidence and every compiled rule.
- Failed verification and audit violations reopen the existing workflow for repair and can escalate staffing seniority.
- An accountability graph connecting project phase, runs, assignments, candidates, evidence, audits, acceptance, and promotion.
- A post-genesis Architect cockpit that projects live company state and exposes only valid commands.
- Greenfield blueprint materialization and explicit local fast-forward promotion after dual conforming audits; Orchard never pushes a remote.
- Append-only company-control recovery and end-to-end tests covering failed work, reassignment, stale evidence, audit repair, restart, and promotion safety.

Milestone 9.5 boundaries:

- The company circuit is local and single-architect; distributed staff coordination and remote control protocols are outside this milestone.
- Orchard promotes only within the bound local repository and does not create branches, pull requests, or remote pushes.
- Company control records staffing, rules, audits, acceptance, escalation, and promotion metadata; existing workflow, evidence, worker, and Git stores remain canonical.
- Product-genesis successor revisions and downstream invalidation remain a later conversational change circuit.

## Milestone 9.4: Guided Product Genesis

The desktop application is now one state-driven Architect circuit rather than a freely navigable delivery dashboard. Conversation forms candidate structure while the backend determines the only legal next transition and the screen continuously projects durable product truth.

Delivered and verified:

- A guided `CLASSIFICATION -> EXPERIENCE -> ARCHITECTURE -> BLUEPRINT -> ADMISSION -> READY` circuit.
- Greenfield-local, existing-local, and organization-governed project classification.
- Existing-local repository binding inside the guided flow; organization-governed admission fails closed pending verified policy sources.
- A structured Experience Contract covering audience, product promise, primary journey, interaction principles, emotional qualities, exclusions, and accessibility commitments.
- First-epic selection as the experience-proving vertical slice before architecture and repository setup.
- Structured components and ADR-like decisions correlated to requirements, dependencies, and repository paths.
- A repository blueprint derived from design, including toolchain, modules, policy packs, and verification commands.
- Bounded local-model genesis proposals that remain candidate data until explicit human application.
- Checksummed append-only genesis revisions with stale-write rejection and exact restart recovery.
- Production workflow admission that rejects manual and durable-dispatch starts until genesis is admitted.
- A stable Compose workspace with a non-clickable progress spine, semantic phase transitions, conversational proposal review, precise fallback controls, and live architecture projection.
- Adversarial tests proving a model cannot skip phases, forge revision authority, mutate state through proposal generation, or bypass the dispatch interlock.

Milestone 9.4 boundaries:

- The desktop shell projects one active local project; multi-client and remote Architect protocols remain later work.
- Repository blueprints are admitted authority but are not yet materialized into new repositories.
- Structured architectural decisions are not yet exported as repository Markdown ADRs.
- Revising an admitted genesis and computing downstream invalidation remains a later conversational change circuit.

## Milestone 1: Local Architect MVP

This milestone establishes Orchard's first complete local workflow: describe delivery intent in the desktop application, interpret it with `phi3:mini`, validate it against deterministic Kotlin policy, and render the resulting hierarchy without a cloud service.

Delivered and verified:

- Compose Desktop project, epic, story, task, and bug views.
- Multiline Architect input with Ctrl+Enter submission.
- Typed Ktor APIs and `kotlinx.serialization` JSON contracts.
- Two-phase local Ollama triage and planning through a suspendable Ktor client.
- Request-local Architect execution with single-flight concurrency protection.
- Deterministic preservation of explicit titles, descriptions, and parent IDs.
- Atomic plans of up to eight ordered operations.
- Default Delivery hierarchy enforcement: `Project -> Epic -> Story -> Task/Bug`.
- Automatic `General` epic creation when a new project and story omit an epic.
- Kotlin-owned IDs, hierarchy normalization, and rollback.
- Local application directories beneath `~/.orchard`.
- Backend and frontend regression suites run through `./gradlew build`.
- Live Ollama verification covers non-streaming JSON requests and exact single-intent creation.

Milestone 1 boundaries:

- Workspace state holds at most 32 entities in process memory.
- Create is the only applied action; update, delete, and query are classified but rejected.
- Ollama must be running locally with `phi3:mini` installed.
- Evidence derivation and downstream agent execution begin after this milestone.

## Milestone 2: Filesystem Authority

Workspace state now has a human-readable authority beneath `~/.orchard/projects/workspace`. The in-memory store is a validated projection recovered at backend startup.

Delivered and verified:

- One checksummed JSONL journal transaction per accepted Architect batch.
- Monotonic transaction sequences and entity IDs across backend restarts.
- Checksummed, human-readable JSON snapshots after 32 transactions.
- Temporary-file writes, flushes, and atomic snapshot replacement.
- Recovery from a snapshot plus later journal transactions.
- Quarantine of a malformed or truncated journal tail while preserving the valid prefix.
- Full hierarchy validation before recovered entities enter the in-memory projection.
- In-memory rollback and a structured `503` response when durable commit fails.
- API snapshots expose only committed entities while a batch is in progress.

Milestone 2 boundaries:

- The current authoritative schema covers the 32-item Default Delivery workspace.
- Database, vector, and embedding state beneath `~/.orchard/db` remains derived and rebuildable.
- Corrupt authoritative snapshots fail startup rather than silently discarding state.

## Milestone 3: Repository Binding

An Orchard Project can now bind to a real local Git repository. The binding is durable authority, while branch, remote, working-tree state, availability, and build-system metadata are refreshed from the repository when the workspace is read.

Delivered and verified:

- Directory selection from the active Project in Compose Desktop.
- Backend validation of absolute, existing directories inside Git worktrees.
- Canonical normalization to the Git top-level directory.
- Checksummed, atomically replaced `repository-bindings.json` authority keyed by Project ID.
- Live branch, origin remote, clean/dirty state, and build-system inspection.
- Gradle, Maven, Meson, CMake, Cargo, and Node build-system detection.
- Missing or moved repositories remain bound and report unavailable without losing project state.
- Read-only Git commands with optional index locking disabled.
- Structured `404`, `422`, and `503` repository-binding outcomes.
- Restart recovery and desktop/backend contract tests.

Milestone 3 boundaries:

- Orchard never fetches, checks out, stages, commits, or writes configuration in a bound repository.
- A Project has at most one active local repository binding.
- Repository metadata is context, not accepted completion evidence.
- Lifecycle transitions and evidence contracts begin in Milestone 4.

## Milestone 4: Governed Workflow Memory

Starting a Task or Bug creates an immutable workflow admission record rather than directly changing a board status. Orchard pins the complete work-item hierarchy and clean repository revision, resolves the built-in task or bug workflow, recalls relevant past work episodes, and durably publishes the run before projecting the item as In Progress. Attempts, evidence, decisions, cancellation, and completion are append-only workflow events.

Delivered and verified:

- Workflow runs with monotonic IDs and checksummed JSONL persistence.
- Immutable context manifests containing Project, Epic, Story, Task/Bug, workflow version, and exact Git revision.
- Separate task and bug evidence contracts; bug work additionally requires regression-test evidence.
- Deterministic Project/type/workflow-scoped recall of up to three similar work episodes.
- Recalled problems, failed approaches, successful resolutions, evidence summaries, and source revisions embedded in the historical run.
- Clean-worktree admission and rejection of missing bindings, unavailable repositories, unborn `HEAD`, duplicate starts, and unsupported entity types.
- Persist-before-publish semantics: a failed append leaves the item in Todo with no visible run.
- Typed attempt and evidence records attached to admitted runs.
- Git validation that evidence targets a real descendant revision with source changes from the pinned context.
- Deterministic gate decisions from evidence kind, command, exit code, producer, revision, and output hash.
- Passing retries supersede earlier failed evidence for the same gate without erasing the failed attempt.
- Event-derived `EVIDENCE_PENDING`, `EVIDENCE_BLOCKED`, `DONE`, and `CANCELLED` run states and board projections.
- Atomic completion decisions and immutable work episodes containing failed approaches, the accepted resolution, evidence summaries, and source revision.
- Compose controls showing run state, pinned revision, gate progress, recalled precedent, and explicit cancellation.
- Restart recovery preserves the original context even when the repository advances to a later revision.
- A real-Git regression completes one Task, restarts the store, and proves a similar Task recalls the generated episode.

Milestone 4 boundaries:

- Orchard records evidence supplied by agents, CI, or other trusted producers; it does not execute build or test commands from evidence payloads.
- Retry appends new evidence to the same run. Cross-run supersession and review approval are not yet modeled.
- Cancellation closes a run without manufacturing a completion episode.
- Approved project practices and repository instructions are not yet resolved into context manifests.

## Milestone 5: System Workflows

System workflows govern how Orchard work becomes eligible for delivery. A Task or Bug no longer starts merely because it exists and has a clean repository. Its latest definition revision must be `READY`, and the accepted manifest is embedded in the resulting delivery run.

Delivered and verified:

- Versioned built-in Task and Bug definition workflows with explicit phases.
- One generic step contract for start conditions, recalled context, allowed executors, evidence, and transition signals.
- Structured definitions for requested outcome, current and required behavior, bounded scope, non-goals, constraints, and acceptance criteria with verification methods.
- Bug-specific reproduction and regression obligations.
- Deterministic `NEEDS_INVESTIGATION`, `NEEDS_CLARIFICATION`, `NEEDS_SPLIT`, and `READY` assessments.
- Append-only, checksummed `work-definitions.jsonl` authority with monotonic definition IDs and per-item revisions.
- Latest-revision semantics: a later ambiguous revision blocks delivery even if an earlier revision was ready.
- Delivery admission rejects missing or non-ready definitions and closes definition revision after delivery starts.
- New delivery runs embed the exact accepted definition manifest and hash.
- Definition-derived `ACCEPTANCE` evidence gates augment the source, build, test, and Bug regression obligations.
- Every evidence result and cancellation emits a typed transition signal selected from the pinned step policy.
- Desktop definition editor and card-level readiness projection; Start Workflow is offered only at `READY`.
- Real-Git restart coverage proves definitions, pinned runs, acceptance gates, completion episodes, and recall recover together.

### Milestone 6: Collaborative Work Definition authority

Tasks and Bugs now support an iterative human-LLM definition loop before delivery:

- invoke the local model from the work item or definition editor
- inspect model observations separately from assumptions
- record human feedback as a durable artifact
- ask the model for a feedback-aware revision
- edit any proposal into a distinct human-authored revision
- explicitly accept a proposal before deterministic assessment

The workflow step declares actor-specific authority. HUMAN may propose, revise, provide feedback, and accept. LOCAL_LLM may only propose and revise. DETERMINISTIC_POLICY alone assesses completeness and derives the transition signal.

All proposals, revisions, feedback, acceptance records, source hashes, and model provenance are append-only and recovered after restart. Delivery pins the accepted proposal and closes further definition collaboration.

Milestone 6 boundaries:

- Humans currently supply and revise definitions through the desktop or typed API.
- Orchard validates explicit structure and unresolved questions; it does not claim to infer every latent ambiguity from prose.
- `NEEDS_SPLIT` records proposed child titles but does not yet materialize child work items.
- Investigation agents may later gather logs, diagnostics, and reproductions, but their outputs remain observations until accepted through a system workflow.

### Milestone 7: Context-Bounded Model Profiles

The Work Definition workflow now requests `bounded-definition-reasoning-v1` instead of naming a model. Orchard resolves a compatible installed binding and compiles one immutable invocation envelope containing the current workflow step, allowed and forbidden actions, required output schema, and authoritative context.

Delivered and verified:

- Versioned execution profiles separate reasoning requirements from model identity.
- Model bindings declare context capacity, capabilities, provider, inference configuration, and optional digest.
- The definition profile uses a conservative 12,000-token input aperture and reserves 2,000 output tokens.
- Mandatory context overflow fails before inference; Orchard never silently truncates workflow authority.
- Ollama receives an explicit output-token cap and returns prompt/completion token telemetry when available.
- Every attempted inference appends checksummed execution evidence with envelope, prompt, and output hashes, token counts, latency, and schema validity.
- LLM proposal provenance pins the exact execution ID.
- Human feedback, unchanged acceptance, and edited acceptance become distinct satisfaction observations derived from their authoritative journals.
- Capability profiles are rebuilt from raw evidence and show sample count, schema validity, human outcomes, edit distance, median latency, and confidence.
- Sparse routing selects the smallest compatible binding; sufficiently sampled bindings are ordered by schema reliability, human acceptance, unchanged acceptance, and latency.
- Compose Desktop displays the matching model-memory evidence in the collaborative definition editor.
- Restart and torn-tail tests prove the profile projection recovers from the valid journal prefix; interior corruption fails closed without deleting later evidence.

Milestone 7 boundaries:

- Profile memory influences executor selection only; it cannot signal `READY`, accept a definition, or complete a workflow.
- Pre-inference token counting is a conservative estimate until providers expose tokenizer-specific counting.
- At delivery, the production installation bound the definition profile to local `phi3:mini`; current installations use hardware-aware, workload-specific routing.
- Satisfaction currently derives from feedback and acceptance behavior rather than a general rating control.
- Generic benchmarks, leaderboards, fine-tuning, and autonomous code execution remain separate work.

### Milestone 7.1: User-Configurable Model Apertures

Model execution defaults remain versioned workflow policy, while local users can now override the operating aperture according to their machine and task needs.

Delivered and verified:

- Checksummed, atomically replaced `model-profile-settings.json` local authority.
- Separate input aperture and output reasoning reserve controls.
- Optional preferred binding or evidence-aware automatic routing.
- Save-time validation that input plus output fits an installed binding's declared context capacity.
- Ollama explicitly requests the effective input-plus-output aperture rather than its theoretical maximum.
- Workflow-owned reasoning class and required capabilities cannot be overridden.
- Typed model-profile GET/PUT APIs with `404`, `422`, and `503` outcomes.
- Desktop settings dialog showing defaults, effective budgets, installed models, and live draft compatibility.
- Effective budgets and preferred binding apply to the next definition execution without restarting Orchard.
- Capability evidence remains separated by effective aperture, model digest, and inference configuration.
- Restart coverage proves the selected aperture and binding preference recover from disk.

Milestone 7.1 boundaries:

- Declared model context capacity remains the binding compatibility constraint; live admission is governed separately by Milestone 7.2.
- A smaller aperture never truncates mandatory context. It produces explicit context-budget overflow when the invocation cannot fit.
- The desktop currently updates overrides but does not delete the override record; using default budgets restores the default aperture behavior.

### Milestone 7.2: Resource-Aware Parallel Admission

Orchard now combines theoretical model demand, actual machine availability, and user-owned capacity policy before every production local-model invocation.

Delivered and verified:

- Checksummed, atomically replaced `machine-usage-policy.json` authority.
- User-configurable Orchard capacity share from 1% to 100%, minimum free-memory reserve, and maximum concurrent model jobs.
- Live Linux host memory, JVM CPU-load, processor, and process-relative cgroup v2/v1 memory telemetry.
- Most-restrictive cgroup ancestor limits and cgroup usage included in available-capacity calculations.
- Deterministic leases requiring demand to fit both the delegated share of total capacity and live availability after the safety reserve.
- Overflow-safe cumulative RAM and CPU reservations; unknown telemetry fails closed.
- Conservative Ollama demand derived from model residency plus KV-cache aperture, with explicit one-thread execution matching the CPU lease.
- Separate `429` capacity/concurrency outcomes and `503` telemetry/storage outcomes.
- Resource-admission evidence attached to model execution observations without invalidating pre-7.2 journals.
- Per-ticket execution locks permit independent tickets to run concurrently while preventing duplicate inference for one ticket.
- Architect triage and planning use the same Orchard-wide resource policy as Work Definition execution.
- Typed machine-resource GET/PUT APIs and desktop controls showing observed memory, CPU load, active leases, and the latest admission decision.
- Desktop requests can overlap; response ordering prevents stale snapshots from replacing newer state.

Milestone 7.2 boundaries:

- GPU/VRAM placement, thermal pressure, and accelerator-specific utilization are not yet admission inputs.
- The Ollama memory estimate is deliberately conservative and does not yet subtract shared resident-model memory across leases.
- Capacity denial returns a retryable result; a durable autonomous queue and worktree-aware integration scheduler remain the next delivery layer.
- CPU enforcement is achieved by explicit Ollama thread limits and admission reservations, not by Orchard-created cgroups.

### Milestone 8.0: Staged Delivery Circuits

Orchard now records logical execution order before admitting parallel ticket work. Epic plans organize Stories; Story plans organize Tasks and Bugs. The graph is durable authority, while labels such as `1a`, `1b`, and `2a` are derived for people.

Delivered and verified:

- Complete direct-child coverage for Epic-to-Story and Story-to-Task/Bug plans.
- Strict backward dependency wires that reject missing, duplicate, self, same-stage, and forward edges.
- Versioned stage workflow registry for contract design, parallel implementation, integration, and sequential delivery policies.
- Runtime resolution of stage workflow pins; unknown IDs and versions are rejected.
- Story-level typed output declarations and input requirements tied to exact producer nodes and delivery evidence kinds.
- Evidence-backed artifact instances binding producer, workflow run, accepted evidence ID, repository revision, and output hash.
- Downstream admission requires both completed dependencies and every declared artifact instance.
- Append-only checksummed plan authority with monotonic IDs, file locking, forced writes, and restart recovery.
- Single-backend optimistic concurrency through active revision and hash tokens, with stale editor submissions returning `409 Conflict`.
- Historical revision recovery across hierarchy growth, with current coverage enforced against the active revision.
- Explicit cancelled-node retry without permanently locking the circuit.
- Desktop Epic and Story planning actions, registered workflow selection, circuit lanes, eligibility, and artifact signals.
- Bounded stages, nodes, dependencies, and artifact collections at the authority boundary.

Milestone 8.0 boundaries:

- Plans are manually constructed and accepted; automatic Architect decomposition and materialization are future work.
- Eligible nodes are started explicitly; durable automatic dispatch, priorities, and worktree-aware integration queues are future work.
- Stage workflows orchestrate circuit entry and exit. Task and Bug runs retain their own governed delivery workflows and evidence contracts.
- Orchard persists evidence-bound artifact identity, not arbitrary artifact payload bytes.
- Epic circuits currently use completion dependencies only; Epic artifact wires await an explicit Story-output aggregation policy.
- Built-in stage workflows are versioned policy; installing user-authored workflow implementations is not yet supported.
- One Orchard backend process owns each workspace authority directory; cross-process stale-conflict classification is outside this milestone.

### Milestone 8.1: Architect Circuit Synthesis

Orchard can now ask the local Architect to decompose an existing Epic or Story into a reviewable staged circuit proposal. Generation remains proposal-only: deterministic validation and explicit human acceptance are still required before a graph becomes execution authority.

Delivered and verified:

- Dedicated `bounded-circuit-synthesis-v1` reasoning profile with configurable input and output apertures.
- Immutable synthesis envelopes containing exact hierarchy, accepted definitions, valid evidence kinds, active plan base, and registered stage workflows.
- Strict JSON output for stages, nodes, dependency wires, typed artifact signals, observations, and assumptions.
- Deterministic post-generation validation of complete membership, workflow pins, graph ordering, artifact contracts, and authority bounds.
- Append-only checksummed `circuit-proposals.jsonl` authority with monotonic IDs, revisions, model provenance, and restart recovery.
- Model execution evidence for success, invalid output, provider failure, resource denial, token overflow, and cancellation.
- Stale-context rejection when hierarchy or active plan authority changes during inference.
- Proposal-only generation API and a separate explicit acceptance API.
- Desktop generation from Epic and Story planners, visible observations and assumptions, and editable proposal fields.
- Accepted plans pin the source proposal ID and hash and distinguish unchanged acceptance from human-edited acceptance.
- Structural edit-distance evidence feeds model capability memory and future binding selection.
- Cross-journal recovery validates model execution, proposal provenance, and accepted plan references together.

Milestone 8.1 boundaries:

- Synthesis organizes existing children; it does not materialize new Stories, Tasks, or Bugs.
- Epic synthesis emits completion dependencies only until Story output aggregation has an explicit authority model.
- Regeneration appends a new proposal; feedback-threaded circuit revisions and proposal history UX remain future work.
- At delivery, the profile API configured synthesis apertures while the desktop settings dialog focused on the primary Work Definition profile; current settings expose every workload stage.
- Generation does not start eligible nodes. Durable queues, automatic dispatch, worktree isolation, and integration ownership belong to Milestone 8.2.

### Milestone 8.2: Durable Circuit Dispatch

Orchard now turns accepted circuit eligibility into durable execution authority. A node is recorded before its workflow starts, survives restart or temporary repository denial, and remains traceable to the exact plan revision that authorized it.

Delivered and verified:

- Append-only checksummed `circuit-dispatches.jsonl` authority with monotonic IDs, forced writes, and structural restart recovery.
- Idempotent reconciliation from accepted plan, hierarchy, READY Work Definition, dependency, artifact, and stage-policy authority.
- Deterministic dispatch priorities derived from stage and node order.
- Automatic initial workflow starts and evidence-driven downstream fan-out without model judgment.
- A one-second production scheduler that retries pending nodes after transient repository admission failure.
- Immutable workflow context binding to the exact dispatch ID.
- Real Git worktrees and `orchard/circuit-dispatch-*` branches pinned to the admitted clean base revision.
- Distinct isolated workspaces for parallel nodes.
- Exactly one owner node for every `integration-v1` stage and a pinned integration-mode workspace reservation.
- Derived pending, running, done, and cancelled views without mutating dispatch authority.
- Explicit cancelled-node replacement with a new dispatch, workflow run, branch, and worktree.
- Typed desktop projection of queue priority, runtime state, integration ownership, and workspace reservation.
- Filesystem-level worktree tests, restart idempotency tests, scheduler retry tests, cancellation tests, and dependency fan-out tests.

Milestone 8.2 boundaries:

- Dispatch creates the governed workflow run and isolated workspace consumed by the Milestone 9.2 coding worker.
- The integration owner receives an isolated workspace but Orchard does not yet merge, rebase, resolve conflicts, or publish branches.
- Dispatch worktrees and branches are retained; governed archival and cleanup are future work.
- Priority is deterministic circuit order rather than a configurable deadline or scheduling class.
- Local-model resource leases now govern coding inference as well as definition and circuit synthesis.
- Epic circuits gate Story completion; Task and Bug dispatch comes from Story circuits until Story-output aggregation is defined.
- The scheduler is single-backend authority, not a distributed multi-process queue.

### Milestone 9.0: Requirement Authority and Design Admission

Orchard now treats design and requirement decomposition as enforceable system authority. Governance is explicitly activated per Project; activated projects fail closed when implementation authority is absent or stale, while legacy projects preserve their existing workflow until migrated.

Delivered and verified:

- Exact Epic system, Story subsystem, and Task/Bug implementation requirement levels.
- Stable requirement IDs, direct-parent traceability, and complete direct-parent allocation.
- Immutable candidate design revisions with optimistic revision and content-hash concurrency.
- Deterministic design admission with structured, immutable rejection findings.
- Atomic admission and criterion-level acceptance-contract compilation.
- Exact design and parent-design references, inherited requirement IDs, verification methods, and human or automated gates in each contract.
- Append-only checksummed `design-governance.jsonl` authority with forced writes and monotonic IDs.
- Restart recovery that recomputes deterministic findings, design hashes, decision hashes, parent references, and acceptance contracts.
- Manual and circuit-dispatched execution gates that require current admitted Task/Bug authority.
- Immutable workflow contexts that pin the complete acceptance contract.
- Stale-descendant impact projection when a new Epic or Story design is admitted.
- Historical stability for already-started runs under their original pinned contract.
- Typed HTTP operations for project activation, candidate recording, and deterministic admission.

Milestone 9.0 boundaries:

- Deterministic admission proves structure, traceability, allocation, and executable acceptance paths; independent semantic inspection is still required to prove non-weakening, feasibility, and policy consistency.
- The external organizational Git policy source defined in ADR 004 is not yet synchronized or applied.
- Acceptance criteria are pinned into workflow context; criterion-level completion enforcement is delivered in Milestone 9.1.
- A dedicated Compose design-authoring and admission screen remains future UX work; authority is available through the typed backend API and workspace snapshot.
- Parent revisions report stale descendants but do not synthesize replacement child designs.

### Milestone 9.1: Contract-Compiled Acceptance Gates

Orchard now carries admitted requirement authority through completion. Every criterion in the Task or Bug acceptance contract becomes an immutable gate in the resolved workflow, and all built-in, Work Definition, automated criterion, and human criterion gates must pass against one resulting repository revision.

Delivered and verified:

- Version 3 governed delivery workflows compiled from the pinned acceptance contract.
- Stable criterion evidence kinds derived from admitted criterion IDs.
- Exact requirement and criterion traceability in each compiled gate.
- Automated criteria that accept only their admitted verification command and passing revision evidence.
- Human criteria that reject command evidence and require immutable named judgments with rationale.
- Rejected and superseding approved judgments retained in append-only workflow history.
- Completion decisions that pin every contributing evidence and judgment event ID.
- Same-revision composition across source, build, test, Work Definition, automated, and human gates.
- Work episodes containing accepted evidence summaries and human approval rationale.
- Replay validation that rejects invalid criterion, contract, verification, authority-reference, or completion claims.
- Replay-time Git ancestry and outcome revalidation rather than trust in persisted revision strings or pass flags.
- Recoverable-tail quarantine for workflow run, event, and episode journals, with interior corruption still failing closed.
- Typed run projections for `PENDING`, `REJECTED`, and `PASSED` criterion gates.
- Backend and desktop network contracts for recording and inspecting human judgments.

Milestone 9.1 boundaries:

- External producers may still submit evidence; Milestone 9.2 adds Orchard-owned bounded local verification without claiming OS-level sandbox isolation.
- Workflow evidence recovery fails closed when the bound repository or referenced commits are unavailable for ancestry revalidation.
- Named approvers are attributable caller claims, not yet authenticated identities or policy-role proofs.
- Quorum, delegation, segregation of duties, and time-bounded waivers require the policy-composition layer.
- A dedicated Compose judgment control remains future UX work.
- External policy-pack synchronization and source-bound RAG are the next governance arc.

### Milestone 9.2: Governed Autonomous Coding Worker

Orchard now closes the first local execution loop. A circuit-dispatched governed run can be durably claimed, translated by a bounded local model into typed file operations, committed in its reserved Git worktree, verified, and submitted to the existing acceptance-gate engine.

Delivered and verified:

- Append-only checksummed `coding-worker.jsonl` claims and results with monotonic IDs, forced writes, cross-process append locking, restart validation, and torn-tail recovery.
- Claims pinned to run context hash, isolated worktree, model-binding fingerprint, and bounded attempt number before inference.
- `bounded-coding-patch-v1` model profile with strict JSON full-file `WRITE` and `DELETE` proposals.
- Bounded tracked-file context assembly with model execution, resource admission, token, schema, and output provenance.
- Clean-worktree admission, normalized path confinement, symlink and metadata rejection, atomic writes, `git diff --check`, and local candidate commits.
- Run-authority revalidation immediately before mutation to prevent writes after cancellation or context change.
- Argument-vector verification using only pinned commands or validated toolchain policy defaults, with reduced environment, timeout, process-tree termination, and bounded output.
- Evidence submission exclusively through the Milestone 9.1 gate engine; the worker cannot approve human criteria or mark a run complete.
- Canonical Git source-diff hashes re-proved during replay for new worker evidence.
- Bounded immutable repair attempts, persisted cooldowns for transient resource denial, interrupted-claim recovery, and no repeat execution while a completed candidate awaits human judgment.
- Automatic one-second worker scheduling plus typed execution-history and manual-tick APIs.
- Focused real-Git tests for journal replay, duplicate claims, path escape, dirty-index contamination, candidate commits, command execution, and canonical diff hashing.

Milestone 9.2 boundaries:

- Candidate branches are not merged, rebased, pushed, published, conflict-resolved, archived, or cleaned up automatically.
- Verification is bounded but not isolated by an OS filesystem/network sandbox; repository build scripts remain trusted local code.
- The first proposal format replaces complete UTF-8 files rather than applying semantic or language-server edits.
- At delivery, the production binding remained `phi3:mini`; current hardware presets assign a code-specialized binding where machine capacity permits.
- Context assembly uses bounded lexical ranking over tracked text files; source-bound RAG and symbol-aware retrieval remain future work.
- A dedicated Compose coding-worker surface remains part of the upcoming UX effort.

### Milestone 9.3: Runtime Toolchain Policy Packs

Orchard now separates toolchain declarations from its trusted execution engine. Built-in and user-installed packs describe repository detectors and typed build/test commands, while Kotlin continues to enforce filesystem, process, evidence, and replay invariants.

Delivered and verified:

- Strict version 1 JSON schema for pack identity, profile priority, file detectors, evidence kinds, executables, and argument vectors.
- Built-in Gradle, Maven, Cargo, Meson, CMake, and Node profiles moved out of the coding gateway into a resource policy pack.
- Hot discovery of external packs from `~/.orchard/policy-packs/toolchains/*.json` on every future policy resolution.
- Deterministic selection by descending priority, then stable pack and profile identity.
- Fail-closed validation of schema versions, duplicate identities, bounds, traversal, reserved roots, malformed executables, and command arguments.
- Bounded policy-directory scanning, pack-file reads, and streaming verification-output capture.
- Typed command execution without shell expansion or a hard-coded ecosystem executable registry.
- Repository-local executable confinement with regular-file, no-symbolic-link, and executable checks.
- Durable worker claims pinning the selected pack ID, pack version, profile ID, and complete policy hash before inference.
- Exact acceptance-contract commands retaining precedence and exact evidence text while executing as typed arguments.
- Tests proving a previously unknown community toolchain can be added after catalog construction and used without restart or backend code changes.
- Legacy coding-worker journal replay retained across the new policy authority fields.

Milestone 9.3 boundaries:

- External packs are local user-installed execution policy; Orchard does not fetch or activate remote packs automatically.
- Git source allowlisting, revision synchronization, signatures, and a community registry remain future policy-source work.
- Only toolchain verification is pack-backed. Stage workflows, delivery contracts, model catalogs, prompt registries, and classifiers remain subsequent migrations.
- Pack commands are bounded argv, not shell scripts, but repository builds still lack an OS-level filesystem/network sandbox.
- Pack selection is detector-based and not yet exposed as an explicit per-Project UI setting.

## Quick Start

Scripted onboarding supports Linux and macOS. The setup script installs JDK 21+ through SDKMAN when needed and uses native package managers for the remaining prerequisites. Ollama is optional when using another configured provider.

```bash
./setup_orchard.sh
./run_orchard.sh
```

Use `--skip-ollama` with either script for LM Studio or another OpenAI-compatible endpoint. See [Getting Started](docs/user-guide/getting-started.md) for prerequisites, first-project admission, ports, and launcher controls.

## Architecture

Orchard has two Gradle modules:

- `backend`: loopback-only Ktor APIs, governed services, model routing, Git operations, and checksummed local authority.
- `frontend`: Compose Desktop projections, edit buffers, and a typed Ktor client.

The backend owns every authoritative transition. Model output remains candidate data until deterministic validation and any required admission. Coding, verification, independent audit, company acceptance, and local promotion remain separate authorities.

See [Architecture](docs/developer/architecture.md), [API Reference](docs/developer/api-reference.md), and [Persistence and Recovery](docs/developer/persistence.md) for the maintained technical reference.

## Build

```bash
./gradlew build --no-daemon
```

See [Development](docs/developer/development.md) for focused test and component run commands.

## Future Direction

Future direction is maintained only in [ROADMAP.md](ROADMAP.md). README milestone sections record delivered history; roadmap entries describe intent and do not become execution authority until they pass Orchard's existing design, admission, implementation, verification, audit, and promotion controls.